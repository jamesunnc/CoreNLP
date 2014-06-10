package edu.stanford.nlp.ie.crf;

import edu.stanford.nlp.math.ArrayMath;
import edu.stanford.nlp.optimization.AbstractStochasticCachingDiffUpdateFunction;
import edu.stanford.nlp.optimization.HasFeatureGrouping;
import edu.stanford.nlp.util.ArrayUtils;
import edu.stanford.nlp.util.concurrent.*;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.util.Timing;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.Triple;
import edu.stanford.nlp.util.Quadruple;

import java.util.*;

/**
 * @author Jenny Finkel
 *         Mengqiu Wang
 */

public class CRFLogConditionalObjectiveFunction extends AbstractStochasticCachingDiffUpdateFunction implements HasCliquePotentialFunction, HasFeatureGrouping {

  public static final int NO_PRIOR = 0;
  public static final int QUADRATIC_PRIOR = 1;
  /* Use a Huber robust regression penalty (L1 except very near 0) not L2 */
  public static final int HUBER_PRIOR = 2;
  public static final int QUARTIC_PRIOR = 3;
  public static final int DROPOUT_PRIOR = 4;

  // public static final boolean DEBUG2 = true;
  public static final boolean DEBUG2 = false;
  public static final boolean DEBUG3 = false;
  public static final boolean TIMED = false;
  // public static final boolean TIMED = true;
  public static final boolean CONDENSE = true;
  // public static final boolean CONDENSE = false;
  public static boolean VERBOSE = false;

  protected final int prior;
  protected final double sigma;
  protected final double epsilon = 0.1; // You can't actually set this at present
  /** label indices - for all possible label sequences - for each feature */
  protected final List<Index<CRFLabel>> labelIndices;
  protected final Index<String> classIndex;  // didn't have <String> before. Added since that's what is assumed everywhere.
  protected final double[][] Ehat; // empirical counts of all the features [feature][class]
  protected final double[][] E;

  protected double[][] weights;
  protected final int window;
  protected final int numClasses;
  public static Index<String> featureIndex;
  protected final int[] map;
  protected final int[][][][] data;  // data[docIndex][tokenIndex][][]
  protected final double[][][][] featureVal;  // featureVal[docIndex][tokenIndex][][]
  protected final int[][] labels;    // labels[docIndex][tokenIndex]
  protected final int domainDimension;
  protected double[][] eHat4Update, e4Update;

  protected int[][] weightIndices;
  protected double[][] weightSquare;
  protected final String backgroundSymbol;

  protected int[][] featureGrouping = null;

  protected static final double smallConst = 1e-6;
  protected static final double largeConst = 5;

  protected Random rand = new Random(2147483647L);

  @Override
  public double[] initial() {
    double[] initial = new double[domainDimension()];
    for (int i = 0; i < initial.length; i++) {
      initial[i] = rand.nextDouble() + smallConst;
      // initial[i] = generator.nextDouble() * largeConst;
      // initial[i] = -1+2*(i);
      // initial[i] = (i == 0 ? 1 : 0);
    }
    return initial;
  }

  public static int getPriorType(String priorTypeStr) {
    if (priorTypeStr == null) return QUADRATIC_PRIOR;  // default
    if ("QUADRATIC".equalsIgnoreCase(priorTypeStr)) {
      return QUADRATIC_PRIOR;
    } else if ("HUBER".equalsIgnoreCase(priorTypeStr)) {
      return HUBER_PRIOR;
    } else if ("QUARTIC".equalsIgnoreCase(priorTypeStr)) {
      return QUARTIC_PRIOR;
    } else if ("DROPOUT".equalsIgnoreCase(priorTypeStr)) {
      return DROPOUT_PRIOR;
    } else if ("NONE".equalsIgnoreCase(priorTypeStr)) {
      return NO_PRIOR;
    } else if (priorTypeStr.equalsIgnoreCase("lasso") ||
               priorTypeStr.equalsIgnoreCase("ridge") ||
               priorTypeStr.equalsIgnoreCase("ae-lasso") ||
               priorTypeStr.equalsIgnoreCase("sg-lasso") ||
               priorTypeStr.equalsIgnoreCase("g-lasso") ) {
      return NO_PRIOR;
    } else {
      throw new IllegalArgumentException("Unknown prior type: " + priorTypeStr);
    }
  }

  CRFLogConditionalObjectiveFunction(int[][][][] data, int[][] labels, int window, Index<String> classIndex, List<Index<CRFLabel>> labelIndices, int[] map, String backgroundSymbol) {
    this(data, labels, window, classIndex, labelIndices, map, "QUADRATIC", backgroundSymbol);
  }

  CRFLogConditionalObjectiveFunction(int[][][][] data, int[][] labels, int window, Index<String> classIndex, List<Index<CRFLabel>> labelIndices, int[] map, String priorType, String backgroundSymbol) {
    this(data, labels, window, classIndex, labelIndices, map, priorType, backgroundSymbol, 1.0, null);
  }

  CRFLogConditionalObjectiveFunction(int[][][][] data, int[][] labels, int window, Index<String> classIndex, List<Index<CRFLabel>> labelIndices, int[] map, String backgroundSymbol, double sigma, double[][][][] featureVal) {
    this(data, labels, window, classIndex, labelIndices, map, "QUADRATIC", backgroundSymbol, sigma, featureVal);
  }

  CRFLogConditionalObjectiveFunction(int[][][][] data, int[][] labels, int window, Index<String> classIndex, List<Index<CRFLabel>> labelIndices, int[] map, String priorType, String backgroundSymbol, double sigma, double[][][][] featureVal) {
    this.window = window;
    this.classIndex = classIndex;
    this.numClasses = classIndex.size();
    this.labelIndices = labelIndices;
    this.map = map;
    this.data = data;
    this.featureVal = featureVal;
    this.labels = labels;
    this.prior = getPriorType(priorType);
    this.backgroundSymbol = backgroundSymbol;
    this.sigma = sigma;
    // takes docIndex, returns Triple<prob, E, dropoutGrad>
    Ehat = empty2D();
    E = empty2D();
    weights = empty2D();
    empiricalCounts(Ehat);
    int myDomainDimension = 0;
    for (int dim : map) {
      myDomainDimension += labelIndices.get(dim).size();
    }
    domainDimension = myDomainDimension;
  }

  // this used to be computed lazily, but that was clearly erroneous for multithreading!
  @Override
  public int domainDimension() {
    return domainDimension;
  }

  public void combine2DArr(double[][] combineInto, double[][] toBeCombined) {
    for (int i = 0; i < toBeCombined.length; i++)
      for (int j = 0; j < toBeCombined[i].length; j++)
        combineInto[i][j] += toBeCombined[i][j];
  }

  // TODO(mengqiu) add dimension checks
  public void combine2DArr(double[][] combineInto, Map<Integer, double[]> toBeCombined) {
    double[] source = null;
    int key = 0;
    for (Map.Entry<Integer, double[]> entry: toBeCombined.entrySet()) {
      key = entry.getKey();
      source = entry.getValue();
      for (int i = 0; i< source.length; i++)
        combineInto[key][i] += source[i];
    }
  }

  public void combine2DArr(double[][] combineInto, Map<Integer, double[]> toBeCombined, double scale) {
    double[] source = null;
    int key = 0;
    for (Map.Entry<Integer, double[]> entry: toBeCombined.entrySet()) {
      key = entry.getKey();
      source = entry.getValue();
      for (int i = 0; i< source.length; i++)
        combineInto[key][i] += source[i] * scale;
    }
  }

  /**
   * Takes a double array of weights and creates a 2D array where:
   *
   * the first element is the mapped index of the clique size (e.g., node-0, edge-1) matcing featuresIndex i
   * the second element is the number of output classes for that clique size
   *
   * @return a 2D weight array
   */
  public double[][] to2D(double[] weights, List<Index<CRFLabel>> labelIndices, int[] map) {
    double[][] newWeights = new double[map.length][];
    int index = 0;
    for (int i = 0; i < map.length; i++) {
      int labelSize = labelIndices.get(map[i]).size();
      newWeights[i] = new double[labelSize];
      try {
        System.arraycopy(weights, index, newWeights[i], 0, labelSize);
      } catch (Exception ex) {
        System.err.println("weights: " + weights);
        System.err.println("newWeights["+i+"]: " + newWeights[i]);
        throw new RuntimeException(ex);
      }
      index += labelSize;
    }
    return newWeights;
  }

  public double[][] to2D(double[] weights) {
    return to2D(weights, this.labelIndices, this.map);
  }

  public void to2D(double[] weights, List<Index<CRFLabel>> labelIndices, int[] map, double[][] newWeights) {
    int index = 0;
    for (int i = 0; i < map.length; i++) {
      int labelSize = labelIndices.get(map[i]).size();
      try {
        System.arraycopy(weights, index, newWeights[i], 0, labelSize);
      } catch (Exception ex) {
        System.err.println("weights: " + weights);
        System.err.println("newWeights["+i+"]: " + newWeights[i]);
        throw new RuntimeException(ex);
      }
      index += labelSize;
    }
  }

  public void to2D(double[] weights, double[][] newWeights) {
    to2D(weights, this.labelIndices, this.map, newWeights);
  }

  /** Beware: this changes the input weights array in place. */
  public double[][] to2D(double[] weights, double wscale) {
    for (int i = 0; i < weights.length; i++)
      weights[i] = weights[i] * wscale;

    return to2D(weights, this.labelIndices, this.map);
  }

  public static void clear2D(double[][] arr2D) {
    for (int i = 0; i < arr2D.length; i++)
      for (int j = 0; j < arr2D[i].length; j++)
        arr2D[i][j] = 0;
  }

  public static double[] to1D(double[][] weights, int domainDimension) {
    double[] newWeights = new double[domainDimension];
    int index = 0;
    for (double[] weightVector : weights) {
      System.arraycopy(weightVector, 0, newWeights, index, weightVector.length);
      index += weightVector.length;
    }
    return newWeights;
  }

  public double[] to1D(double[][] weights) {
    return to1D(weights, domainDimension());
  }

  public int[][] getWeightIndices()
  {
    if (weightIndices == null) {
      weightIndices = new int[map.length][];
      int index = 0;
      for (int i = 0; i < map.length; i++) {
        weightIndices[i] = new int[labelIndices.get(map[i]).size()];
        for (int j = 0; j < labelIndices.get(map[i]).size(); j++) {
          weightIndices[i][j] = index;
          index++;
        }
      }
    }
    return weightIndices;
  }

  protected double[][] empty2D() {
    double[][] d = new double[map.length][];
    // int index = 0;
    for (int i = 0; i < map.length; i++) {
      d[i] = new double[labelIndices.get(map[i]).size()];
    }
    return d;
  }

  protected void empiricalCounts(double[][] eHat) {
    for (int m = 0; m < data.length; m++) {
      empiricalCountsForADoc(eHat, m);
    }
  }

  private void empiricalCountsForADoc(double[][] eHat, int docIndex) {
    int[][][] docData = data[docIndex];
    int[] docLabels = labels[docIndex];
    int[] windowLabels = new int[window];
    Arrays.fill(windowLabels, classIndex.indexOf(backgroundSymbol));
    double[][][] featureValArr = null;
    if (featureVal != null)
      featureValArr = featureVal[docIndex];

    if (docLabels.length>docData.length) { // only true for self-training
      // fill the windowLabel array with the extra docLabels
      System.arraycopy(docLabels, 0, windowLabels, 0, windowLabels.length);
      // shift the docLabels array left
      int[] newDocLabels = new int[docData.length];
      System.arraycopy(docLabels, docLabels.length-newDocLabels.length, newDocLabels, 0, newDocLabels.length);
      docLabels = newDocLabels;
    }
    for (int i = 0; i < docData.length; i++) {
      System.arraycopy(windowLabels, 1, windowLabels, 0, window - 1);
      windowLabels[window - 1] = docLabels[i];
      for (int j = 0; j < docData[i].length; j++) {
        int[] cliqueLabel = new int[j + 1];
        System.arraycopy(windowLabels, window - 1 - j, cliqueLabel, 0, j + 1);
        CRFLabel crfLabel = new CRFLabel(cliqueLabel);
        int labelIndex = labelIndices.get(j).indexOf(crfLabel);
        //System.err.println(crfLabel + " " + labelIndex);
        for (int n = 0; n < docData[i][j].length; n++) {
          double fVal = 1.0;
          if (featureValArr != null && j == 0) // j == 0 because only node features gets feature values
            fVal = featureValArr[i][j][n];
          eHat[docData[i][j][n]][labelIndex] += fVal;
        }
      }
    }
  }

  @Override
  public CliquePotentialFunction getCliquePotentialFunction(double[] x) {
    // double[][] weights = to2D(x);
    to2D(x, weights);
    return new LinearCliquePotentialFunction(weights);
  }

  public double valueForADoc(double[][] weights, int docIndex) {
    return expectedCountsAndValueForADoc(weights, null, docIndex, true, false);
  }

  private double expectedCountsAndValueForADoc(double[][] weights, double[][] E, int docIndex) {
    return expectedCountsAndValueForADoc(weights, E, docIndex, false, false);
  }

  private double expectedCountsForADoc(double[][] weights, double[][] E, int docIndex) {
    return expectedCountsAndValueForADoc(weights, E, docIndex, false, true);
  }

  private double expectedCountsAndValueForADoc(double[][] weights, double[][] E, int docIndex, boolean skipExpectedCountCalc, boolean skipValCalc) {
    double prob = 0.0;
    int[][][] docData = data[docIndex];
    int[] docLabels = labels[docIndex];

    double[][][] featureVal3DArr = null;
    if (featureVal != null)
      featureVal3DArr = featureVal[docIndex];

    CliquePotentialFunction cliquePotentialFunc = new LinearCliquePotentialFunction(weights);
    // make a clique tree for this document
    CRFCliqueTree cliqueTree = CRFCliqueTree.getCalibratedCliqueTree(docData, labelIndices, numClasses, classIndex, backgroundSymbol, cliquePotentialFunc, featureVal3DArr);

    if (!skipValCalc) {
      // compute the log probability of the document given the model with the parameters x
      int[] given = new int[window - 1];
      Arrays.fill(given, classIndex.indexOf(backgroundSymbol));
      if (docLabels.length>docData.length) { // only true for self-training
        // fill the given array with the extra docLabels
        System.arraycopy(docLabels, 0, given, 0, given.length);
        // shift the docLabels array left
        int[] newDocLabels = new int[docData.length];
        System.arraycopy(docLabels, docLabels.length-newDocLabels.length, newDocLabels, 0, newDocLabels.length);
        docLabels = newDocLabels;
      }

      double startPosLogProb = cliqueTree.logProbStartPos();
      if (VERBOSE)
        System.err.printf("P_-1(Background) = % 5.3f\n", startPosLogProb);
      prob += startPosLogProb;

      // iterate over the positions in this document
      for (int i = 0; i < docData.length; i++) {
        int label = docLabels[i];
        double p = cliqueTree.condLogProbGivenPrevious(i, label, given);
        if (VERBOSE) {
          System.err.println("P(" + label + "|" + ArrayMath.toString(given) + ")=" + p);
        }
        prob += p;
        System.arraycopy(given, 1, given, 0, given.length - 1);
        given[given.length - 1] = label;
      }
    }

    if (!skipExpectedCountCalc) {
      // compute the expected counts for this document, which we will need to compute the derivative
      // iterate over the positions in this document
      for (int i = 0; i < docData.length; i++) {
        // for each possible clique at this position
        for (int j = 0; j < docData[i].length; j++) {
          Index<CRFLabel> labelIndex = labelIndices.get(j);
          // for each possible labeling for that clique
          for (int k = 0; k < labelIndex.size(); k++) {
            int[] label = labelIndex.get(k).getLabel();
            double p = cliqueTree.prob(i, label); // probability of these labels occurring in this clique with these features
            for (int n = 0; n < docData[i][j].length; n++) {
              double fVal = 1.0;
              if (j == 0 && featureVal3DArr != null) // j == 0 because only node features gets feature values
                fVal = featureVal3DArr[i][j][n];
              E[docData[i][j][n]][k] += p * fVal;
            }
          }
        }
      }
    }

    return prob;
  }

  /**
   * Calculates both value and partial derivatives at the point x, and save them internally.
   */
  @Override
  public void calculate(double[] x) {

    double prob = 0.0; // the log prob of the sequence given the model, which is the negation of value at this point
    // final double[][] weights = to2D(x);
    to2D(x, weights);

    // the expectations over counts
    // first index is feature index, second index is of possible labeling
    // double[][] E = empty2D();
    clear2D(E);

    for (int m = 0; m < data.length; m++) {
      prob += expectedCountsAndValueForADoc(weights, E, m);
    }

    if (Double.isNaN(prob)) { // shouldn't be the case
      throw new RuntimeException("Got NaN for prob in CRFLogConditionalObjectiveFunction.calculate()" +
              " - this may well indicate numeric underflow due to overly long documents.");
    }

    // because we minimize -L(\theta)
    value = -prob;
    if (VERBOSE) {
      System.err.println("value is " + Math.exp(-value));
    }

    // compute the partial derivative for each feature by comparing expected counts to empirical counts
    int index = 0;
    for (int i = 0; i < E.length; i++) {
      for (int j = 0; j < E[i].length; j++) {
        // because we minimize -L(\theta)
        derivative[index] = (E[i][j] - Ehat[i][j]);
        if (VERBOSE) {
          System.err.println("deriv(" + i + "," + j + ") = " + E[i][j] + " - " + Ehat[i][j] + " = " + derivative[index]);
        }
        index++;
      }
    }

    applyPrior(x, 1.0);
  }

  @Override
  public void calculateStochastic(double[] x, double [] v, int[] batch){
    calculateStochasticGradientLocal(x,batch);
  }

  @Override
  public int dataDimension(){
    return data.length;
  }

  private void calculateStochasticGradientLocal(double[] x, int[] batch) {

    double prob = 0.0; // the log prob of the sequence given the model, which is the negation of value at this point
    double[][] weights = to2D(x);

    double batchScale = ((double) batch.length)/((double) this.dataDimension());

    // the expectations over counts
    // first index is feature index, second index is of possible labeling
    double[][] E = empty2D();
    // iterate over all the documents
    for (int ind : batch) {
      //TODO(mengqiu) currently this doesn't taken into account gradient updates at all, need to do gradient
      prob += valueForADoc(weights, ind);
    }

    if (Double.isNaN(prob)) { // shouldn't be the case
      throw new RuntimeException("Got NaN for prob in CRFLogConditionalObjectiveFunction.calculate()");
    }

    value = -prob;

    // compute the partial derivative for each feature by comparing expected counts to empirical counts
    int index = 0;
    for (int i = 0; i < E.length; i++) {
      for (int j = 0; j < E[i].length; j++) {
        // real gradient should be empirical-expected;
        // but since we minimize -L(\theta), the gradient is -(empirical-expected)
        derivative[index++] = (E[i][j] - batchScale*Ehat[i][j]);
        if (VERBOSE) {
          System.err.println("deriv(" + i + "," + j + ") = " + E[i][j] + " - " + Ehat[i][j] + " = " + derivative[index - 1]);
        }
      }
    }

    applyPrior(x, batchScale);
  }

  // re-initialization is faster than Arrays.fill(arr, 0)
  private void clearUpdateEs() {
    for (int i = 0; i < eHat4Update.length; i++)
      eHat4Update[i] = new double[eHat4Update[i].length];
    for (int i = 0; i < e4Update.length; i++)
      e4Update[i] = new double[e4Update[i].length];
  }

  /**
   * Performs stochastic update of weights x (scaled by xscale) based
   * on samples indexed by batch.
   * NOTE: This function does not do regularization (regularization is done by the minimizer).
   *
   * @param x - unscaled weights
   * @param xscale - how much to scale x by when performing calculations
   * @param batch - indices of which samples to compute function over
   * @param gscale - how much to scale adjustments to x
   * @return value of function at specified x (scaled by xscale) for samples
   */
  @Override
  public double calculateStochasticUpdate(double[] x, double xscale, int[] batch, double gscale) {
    double prob = 0.0; // the log prob of the sequence given the model, which is the negation of value at this point
    // int[][] wis = getWeightIndices();
    double[][] weights = to2D(x, xscale);

    if (eHat4Update == null) {
      eHat4Update = empty2D();
      e4Update = new double[eHat4Update.length][];
      for (int i = 0; i < e4Update.length; i++)
        e4Update[i] = new double[eHat4Update[i].length];
    } else {
      clearUpdateEs();
    }

    // Adjust weight by -gscale*gradient
    // gradient is expected count - empirical count
    // so we adjust by + gscale(empirical count - expected count)

    // iterate over all the documents
    for (int ind : batch) {
      // clearUpdateEs();

      empiricalCountsForADoc(eHat4Update, ind);
      // TOOD(mengqiu) this is broken right now
      prob += valueForADoc(weights, ind);

      /* the commented out code below is to iterate over the batch docs instead of iterating over all
         parameters at the end, which is more efficient; but it would also require us to clearUpdateEs()
         for each document, which is likely to out-weight the cost of iterating over params once at the end

      for (int i = 0; i < data[ind].length; i++) {
        // for each possible clique at this position
        for (int j = 0; j < data[ind][i].length; j++) {
          Index labelIndex = labelIndices.get(j);
          // for each possible labeling for that clique
          for (int k = 0; k < labelIndex.size(); k++) {
            for (int n = 0; n < data[ind][i][j].length; n++) {
              // Adjust weight by (eHat-e)*gscale (empirical count minus expected count scaled)
              int fIndex = docData[i][j][n];
              x[wis[fIndex][k]] += (eHat4Update[fIndex][k] - e4Update[fIndex][k]) * gscale;
            }
          }
        }
      }
      */
    }

    if (Double.isNaN(prob)) { // shouldn't be the case
      throw new RuntimeException("Got NaN for prob in CRFLogConditionalObjectiveFunction.calculate()");
    }

    value = -prob;

    int index = 0;
    for (int i = 0; i < e4Update.length; i++) {
      for (int j = 0; j < e4Update[i].length; j++) {
        // real gradient should be empirical-expected;
        // but since we minimize -L(\theta), the gradient is -(empirical-expected)
        // the update to x(t) = x(t-1) - g(t), and therefore is --(empirical-expected) = (empirical-expected)
        x[index++] += (eHat4Update[i][j] - e4Update[i][j]) * gscale;
      }
    }

    return value;
  }

  /**
   * Performs stochastic gradient update based
   * on samples indexed by batch, but does not apply regularization.
   *
   * @param x - unscaled weights
   * @param batch - indices of which samples to compute function over
   */
  @Override
  public void calculateStochasticGradient(double[] x, int[] batch) {
    if (derivative == null) {
      derivative = new double[domainDimension()];
    }
    // int[][] wis = getWeightIndices();
    // was: double[][] weights = to2D(x, 1.0); // but 1.0 should be the same as omitting 2nd parameter....
    double[][] weights = to2D(x);

    if (eHat4Update == null) {
      eHat4Update = empty2D();
      e4Update = new double[eHat4Update.length][];
      for (int i = 0; i < e4Update.length; i++)
        e4Update[i] = new double[eHat4Update[i].length];
    } else {
      clearUpdateEs();
    }

    // Adjust weight by -gscale*gradient
    // gradient is expected count - empirical count
    // so we adjust by + gscale(empirical count - expected count)

    // iterate over all the documents
    for (int ind : batch) {
      // clearUpdateEs();

      empiricalCountsForADoc(eHat4Update, ind);
      // TODO(mengqiu) broken, does not do E calculation
      expectedCountsForADoc(weights, e4Update, ind);

      /* the commented out code below is to iterate over the batch docs instead of iterating over all
         parameters at the end, which is more efficient; but it would also require us to clearUpdateEs()
         for each document, which is likely to out-weight the cost of iterating over params once at the end

      for (int i = 0; i < data[ind].length; i++) {
        // for each possible clique at this position
        for (int j = 0; j < data[ind][i].length; j++) {
          Index labelIndex = labelIndices.get(j);
          // for each possible labeling for that clique
          for (int k = 0; k < labelIndex.size(); k++) {
            for (int n = 0; n < data[ind][i][j].length; n++) {
              // Adjust weight by (eHat-e)*gscale (empirical count minus expected count scaled)
              int fIndex = docData[i][j][n];
              x[wis[fIndex][k]] += (eHat4Update[fIndex][k] - e4Update[fIndex][k]) * gscale;
            }
          }
        }
      }
      */
    }

    int index = 0;
    for (int i = 0; i < e4Update.length; i++) {
      for (int j = 0; j < e4Update[i].length; j++) {
        // real gradient should be empirical-expected;
        // but since we minimize -L(\theta), the gradient is -(empirical-expected)
        // the update to x(t) = x(t-1) - g(t), and therefore is --(empirical-expected) = (empirical-expected)
        derivative[index++] = (-eHat4Update[i][j] + e4Update[i][j]);
      }
    }
  }

  /**
   * Computes value of function for specified value of x (scaled by xscale)
   * only over samples indexed by batch.
   * NOTE: This function does not do regularization (regularization is done by the minimizer).
   *
   * @param x - unscaled weights
   * @param xscale - how much to scale x by when performing calculations
   * @param batch - indices of which samples to compute function over
   * @return value of function at specified x (scaled by xscale) for samples
   */
  @Override
  public double valueAt(double[] x, double xscale, int[] batch) {
    double prob = 0.0; // the log prob of the sequence given the model, which is the negation of value at this point
    // int[][] wis = getWeightIndices();
    double[][] weights = to2D(x, xscale);

    // iterate over all the documents
    for (int ind : batch) {
      prob += valueForADoc(weights, ind);
    }

    if (Double.isNaN(prob)) { // shouldn't be the case
      throw new RuntimeException("Got NaN for prob in CRFLogConditionalObjectiveFunction.calculate()");
    }

    value = -prob;
    return value;
  }

  @Override
  public int[][] getFeatureGrouping() {
    if (featureGrouping != null)
      return featureGrouping;
    else {
      int[][] fg = new int[1][];
      fg[0] = ArrayMath.range(0, domainDimension());
      return fg;
    }
  }

  public void setFeatureGrouping(int[][] fg) {
    this.featureGrouping = fg;
  }

  protected void applyPrior(double[] x, double batchScale) {
    // incorporate priors
    if (prior == QUADRATIC_PRIOR) {
      double sigmaSq = sigma * sigma;
      double lambda = 1 / 2.0 / sigmaSq;
      for (int i = 0; i < x.length; i++) {
        double w = x[i];
        value += batchScale * w * w * lambda;
        derivative[i] += batchScale * w / sigmaSq;
      }
    } else if (prior == HUBER_PRIOR) {
      double sigmaSq = sigma * sigma;
      for (int i = 0; i < x.length; i++) {
        double w = x[i];
        double wabs = Math.abs(w);
        if (wabs < epsilon) {
          value += batchScale*w * w / 2.0 / epsilon / sigmaSq;
          derivative[i] += batchScale*w / epsilon / sigmaSq;
        } else {
          value += batchScale*(wabs - epsilon / 2) / sigmaSq;
          derivative[i] += batchScale*((w < 0.0) ? -1.0 : 1.0) / sigmaSq;
        }
      }
    } else if (prior == QUARTIC_PRIOR) {
      double sigmaQu = sigma * sigma * sigma * sigma;
      double lambda = 1 / 2.0 / sigmaQu;
      for (int i = 0; i < x.length; i++) {
        double w = x[i];
        value += batchScale * w * w * w * w * lambda;
        derivative[i] += batchScale * w / sigmaQu;
      }
    }
  }
}
