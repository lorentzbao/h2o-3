package hex.schemas;

import hex.Model;
import hex.grid.Grid;
import water.H2O;
import water.Key;
import water.api.API;
import water.api.schemas3.JobV3;
import water.api.schemas3.KeyV3;
import water.api.schemas3.ModelParametersSchemaV3;
import water.api.schemas3.SchemaV3;
import water.exceptions.H2OIllegalArgumentException;
import water.util.IcedHashMap;

import java.util.*;
import java.util.stream.Stream;

import static hex.grid.HyperSpaceWalker.BaseWalker.SUBSPACES;
import static water.api.API.Direction.INOUT;
import static water.api.API.Direction.INPUT;

/**
 * This is a common grid search schema composed of two parameters: default parameters for a builder
 * and hyper parameters which are given as a mapping from parameter name to list of possible
 * values.
 * <p>
 * TODO: this needs a V99 subclass for bindings generation.
 *
 * @param <G>  a specific implementation type for GridSearch holding results of grid search (model list)
 * @param <S>  self type
 * @param <MP> actual model parameters type
 * @param <P>  a specific model builder parameters schema, since we cannot derive it from P
 */
public class GridSearchSchema<G extends Grid<MP>,
    S  extends GridSearchSchema<G, S, MP, P>,
    MP extends Model.Parameters,
    P  extends ModelParametersSchemaV3> extends SchemaV3<G, S> {

  //
  // Inputs
  //
  @API(help = "Basic model builder parameters.", direction = INPUT)
  public P parameters;

  @API(help = "Grid search parameters.", direction = INOUT)
  public IcedHashMap<String, Object[]> hyper_parameters;

  @API(help = "Destination id for this grid; auto-generated if not specified.", direction = INOUT)
  public KeyV3.GridKeyV3 grid_id;

  @API(help="Hyperparameter search criteria, including strategy and early stopping directives. If it is not given, " +
      "exhaustive Cartesian is used.", direction = INOUT)
  public HyperSpaceSearchCriteriaV99 search_criteria;

  @API(help = "Level of parallelism during grid model building. 1 = sequential building (default). 0 for adaptive " +
      "parallelism. Any number > 1 sets the exact number of models built in parallel.")
  public int parallelism;
  
  @API(help= "Path to a directory where grid will save everything necessary to resume training after cluster crash.", 
      direction = INPUT)
  public String recovery_dir;
  
  //
  // Outputs
  //
  @API(help = "Number of all models generated by grid search.", direction = API.Direction.OUTPUT)
  public int total_models;

  @API(help = "Job Key.", direction = API.Direction.OUTPUT)
  public JobV3 job;

  private static final int SEQUENTIAL_GRID_SEARCH = 1; // 1 model built at a time = sequential :)

  private static Map<String, Object[]> paramValuesToArray(Map<String, Object> params) {
    Map<String, Object[]> result = new HashMap<>();
    for (Map.Entry<String, Object> e : params.entrySet()) {
      String k = e.getKey();
      Object v = e.getValue();
      Object[] arr = SUBSPACES.equals(k) ? ((List) v).stream().map(x -> paramValuesToArray((Map<String, Object>) x)).toArray(Map[]::new)
              : v instanceof List ? ((List) v).toArray()
              : new Object[]{v};
      result.put(k, arr);
    }
    return result;
  }
  
  @Override public S fillFromParms(Properties parms) {
    if( parms.containsKey("hyper_parameters") ) {
      Map<String, Object> m;
      try {
        m = water.util.JSONUtils.parse(parms.getProperty("hyper_parameters"));
        // Convert lists and singletons into arrays
        hyper_parameters.putAll(paramValuesToArray(m));
      }
      catch (Exception e) {
        // usually JsonSyntaxException, but can also be things like IllegalStateException or NumberFormatException
        throw new H2OIllegalArgumentException("Can't parse the hyper_parameters dictionary; got error: " + e.getMessage() + " for raw value: " + parms.getProperty("hyper_parameters"));
      }
      parms.remove("hyper_parameters");
    }

    if( parms.containsKey("search_criteria") ) {
      Properties p;
      
      try {
        p = water.util.JSONUtils.parseToProperties(parms.getProperty("search_criteria"));

        if (! p.containsKey("strategy")) {
          throw new H2OIllegalArgumentException("search_criteria.strategy", "null");
        }

        // TODO: move this into a factory method in HyperSpaceSearchCriteriaV99
        String strategy = (String)p.get("strategy");
        if ("Cartesian".equals(strategy)) {
          search_criteria = new HyperSpaceSearchCriteriaV99.CartesianSearchCriteriaV99();
        } else if ("RandomDiscrete".equals(strategy)) {
          search_criteria = new HyperSpaceSearchCriteriaV99.RandomDiscreteValueSearchCriteriaV99();
          if (p.containsKey("max_runtime_secs") && Double.parseDouble((String) p.get("max_runtime_secs"))<0) {
            throw new H2OIllegalArgumentException("max_runtime_secs must be >= 0 (0 for unlimited time)", strategy);
          }
          if (p.containsKey("max_models") && Integer.parseInt((String) p.get("max_models"))<0) {
            throw new H2OIllegalArgumentException("max_models must be >= 0 (0 for all models)", strategy);
          }
        } else {
          throw new H2OIllegalArgumentException("search_criteria.strategy", strategy);
        }
        
        search_criteria.fillWithDefaults();
        search_criteria.fillFromParms(p);
      }
      catch (Exception e) {
        // usually JsonSyntaxException, but can also be things like IllegalStateException or NumberFormatException
        throw new H2OIllegalArgumentException("Can't parse the search_criteria dictionary; got error: " + e.getMessage() + " for raw value: " + parms.getProperty("search_criteria"));
      }

      parms.remove("search_criteria");
    } else {
      // Fall back to Cartesian if there's no search_criteria specified.
      search_criteria = new HyperSpaceSearchCriteriaV99.CartesianSearchCriteriaV99();
    }

    if (parms.containsKey("grid_id")) {
      grid_id = new KeyV3.GridKeyV3(Key.<Grid>make(parms.getProperty("grid_id")));
      parms.remove("grid_id");
    }

    if (parms.containsKey("parallelism")) {
      final String parallelismProperty = parms.getProperty("parallelism");
      try {
        this.parallelism = Integer.parseInt(parallelismProperty);
        if (this.parallelism < 0) {
          throw new IllegalArgumentException(String.format("Parallelism level must be >= 0. Given value: '%d'",
                  parallelism));
        }
      } catch (NumberFormatException e) {
        final String errorMessage = String.format("Could not parse given parallelism value: '%s' - not a number.",
                parallelismProperty);
        throw new IllegalArgumentException(errorMessage, e);
      }
      parms.remove("parallelism");
    } else {
      this.parallelism = SEQUENTIAL_GRID_SEARCH;
    }

    if (parms.containsKey("recovery_dir")) {
      this.recovery_dir = parms.getProperty("recovery_dir");
      parms.remove("recovery_dir");
    }

    // Do not check validity of parameters, GridSearch is tolerant of bad
    // parameters (on purpose, many hyper-param points in the grid might be
    // illegal for whatever reason).
    this.parameters.fillFromParms(parms, false);

    return (S) this;
  }

  @Override public S fillFromImpl(G impl) {
    throw H2O.unimpl();
    //S s = super.fillFromImpl(impl);
    //s.parameters = createParametersSchema();
    //s.parameters.fillFromImpl((MP) parameters.createImpl());
    //return s;
  }
}
