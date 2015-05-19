package BIDMach.models

import BIDMat.{ CMat, CSMat, DMat, Dict, IDict, FMat, GMat, GIMat, GSMat, HMat, IMat, Mat, SMat, SBMat, SDMat }
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMat.Solvers._
import BIDMat.Plotting._
import BIDMach._
import BIDMach.datasources._
import BIDMach.updaters._
import java.io._
import scala.util.Random
import scala.collection.mutable._

/**
 * A Bayesian Network implementation with fast parallel Gibbs Sampling (e.g., for MOOC data).
 * 
 * Haoyu Chen and Daniel Seita are building off of Huasha Zhao's original code.
 * 
 * The input needs to be (1) a graph, (2) a sparse data matrix, and (3) a states-per-node file
 * 
 * This is still a WIP.
 */

// Put general reminders here:
// TODO Check if all these (opts.useGPU && Mat.hasCUDA > 0) tests are necessary.
// TODO Investigate opts.nsampls. For now, have to do batchSize * opts.nsampls or something like that.
// TODO Check if the BayesNet should be re-using the user for now
// To be honest, right now it's far easier to assume the datasource is providing us with a LENGTH ONE matrix each step
// TODO Be sure to check if we should be using SMats, GMats, etc. ANYWHERE we use Mats.
// TODO Worry about putback and multiple iterations LATER
class BayesNetMooc3(val dag:Mat, 
                    val states:Mat, 
                    override val opts:BayesNetMooc3.Opts = new BayesNetMooc3.Options) extends Model(opts) {

  var graph:Graph1 = null
  var mm:Mat = null
  var iproject:Mat = null
  var pproject:Mat = null
  var cptOffset:IMat = null
  var statesPerNode:IMat = IMat(states)
  var normConstMatrix:SMat = null
  
  /**
   * Performs a series of initialization steps, such as building the iproject/pproject matrices,
   * setting up a randomized (but normalized) CPT, and randomly sampling users if our datasource
   * provides us with an array of two or more matrices.
   */
  def init() = {
    // build graph and the iproject/pproject matrices from it
    graph = dag match {
      case dd:SMat => new Graph1(dd, opts.dim, statesPerNode)
      case _ => throw new RuntimeException("dag not SMat")
    }
    graph.color
    // TODO Problem is that GSMat has no transpose method! Use CPU Matrices for now!
    iproject = if (opts.useGPU && Mat.hasCUDA > 0) GSMat(graph.iproject) else graph.iproject
    pproject = if (opts.useGPU && Mat.hasCUDA > 0) GSMat(graph.pproject) else graph.pproject

    // build the cpt (which is the modelmats) and the cptoffset vectors
    val numSlotsInCpt = if (opts.useGPU && Mat.hasCUDA > 0) {
      GIMat(exp(GMat((pproject.t)) * ln(GMat(statesPerNode))) + 1e-3) 
    } else {
      IMat(exp(DMat(full(pproject.t)) * ln(DMat(statesPerNode))) + 1e-3)     
    }
    cptOffset = izeros(graph.n, 1)
    if (opts.useGPU && Mat.hasCUDA > 0) {
      cptOffset(1 until graph.n) = cumsum(GMat(numSlotsInCpt))(0 until graph.n-1)
    } else {
      cptOffset(1 until graph.n) = cumsum(IMat(numSlotsInCpt))(0 until graph.n-1)
    }

    val lengthCPT = sum(numSlotsInCpt).dv.toInt
    var cpt = rand(lengthCPT,1)
    normConstMatrix = getNormConstMatrix(cpt)
    cpt = cpt / (normConstMatrix * cpt)
    setmodelmats(new Array[Mat](1))
    modelmats(0) = if (opts.useGPU && Mat.hasCUDA > 0) GMat(cpt) else cpt
    mm = modelmats(0)
    updatemats = new Array[Mat](1)
    updatemats(0) = mm.zeros(mm.nrows, mm.ncols)
  }

  /**
   * Does a uupdate/mupdate on a datasource segment, called from dobatchg() in Model.scala. Note that the
   * data we get in mats(0) will be such that 0s represent unknowns, but we later want -1 to mean that.
   * The point of this method is to compute an update for the updater to improve the mm (the cpt here).
   * Also note that we are randomizing the state/user matrix here, which is like initializing an assignment
   * to the variables, which (ideally) should only be done if ipass = 0.
   * 
   * @param mats An array of matrices representing a segment of the original data.
   * @param ipass The current pass over the data source (not the Gibbs sampling iteration number).
   * @param here The total number of elements seen so far, including the ones in this current batch.
   */
  def dobatch(mats:Array[Mat], ipass:Int, here:Long) = {
    val sdata = mats(0)
    var state = rand(sdata.nrows, sdata.ncols * opts.nsampls)
    if (opts.useGPU && Mat.hasCUDA > 0) {
      state = min( FMat(trunc(statesPerNode *@ state)) , statesPerNode-1) // TODO Why is GMat not working?
    } else {
      state = min( FMat(trunc(statesPerNode *@ state)) , statesPerNode-1)
    }

    // Create and use kdata which is sdata-1, since we want -1 to represent an unknown value.
    val kdata = if (opts.useGPU && Mat.hasCUDA > 0) {
      GMat(sdata.copy) - 1
    } else {
      FMat(sdata.copy) - 1
    }
    val nonNegativeIndices = find(FMat(kdata) >= 0)
    for (i <- 0 until opts.nsampls) {
      state(nonNegativeIndices + i*(kdata.nrows*kdata.ncols)) = kdata(nonNegativeIndices)
    }

    uupdate(kdata, state, ipass)
    mupdate(kdata, state, ipass)
  }

  /**
   * Does a uupdate/evalfun on a datasource segment, called from evalbatchg() in Model.scala.
   * EDIT: do we even want a uupdate call? TODO Should finish this implementaiton.
   * 
   * @param mats An array of matrices representing a segment of the original data.
   * @param ipass The current pass over the data source (not the Gibbs sampling iteration number).
   * @param here The total number of elements seen so far, including the ones in this current batch.
   * @return The log likelihood of the data on this datasource segment (i.e., sdata).
   */
  def evalbatch(mats:Array[Mat], ipass:Int, here:Long):FMat = {
    println("Inside evalbatch, right now nothing here (debugging) but later we'll report a score.")
    return 1f
    /*
    val sdata = mats(0)
    val user = if (mats.length > 1) mats(1) else BayesNetMooc3.reuseuser(mats(0), opts.dim, 1f)
    uupdate(sdata, user, ipass)
    evalfun(sdata, user)
    */
  }

  /**
   * Performs a complete, parallel Gibbs sampling over the color groups, for opts.uiter iterations over 
   * the current batch of data. For a given color group, we need to know the maximum state number. The
   * sampling process will assign new values to the "user" matrix.
   * 
   * @param kdata A data matrix of this batch where a -1 indicates an unknown value, and {0,1,...,k} are
   *    the known values. Each row represents a random variable.
   * @param user Another data matrix with the same number of rows as kdata, and whose columns represent
   *    various iid assignments to all the variables. The known values of kdata are inserted in the same
   *    spots in this matrix, but the unknown values are appropriately randomized to be in {0,1,...,k}.
   * @param ipass The current pass over the full data source (not the Gibbs sampling iteration number).
   */
  def uupdate(kdata:Mat, user:Mat, ipass:Int):Unit = {
    var numGibbsIterations = opts.samplingRate
    if (ipass == 0) {
      numGibbsIterations = numGibbsIterations + opts.numSamplesBurn
    }
    for (k <- 0 until numGibbsIterations) {
      println("In uupdate, Gibbs iteration " + k)
      for (c <- 0 until graph.ncolors) {
        val idInColor = find(graph.colors == c)
        val numState = IMat(maxi(maxi(statesPerNode(idInColor),1),2)).v
        var stateSet = new Array[Mat](numState)
        var pSet = new Array[Mat](numState)
        var pMatrix = zeros(idInColor.length, kdata.ncols * opts.nsampls)
        for (i <- 0 until numState) {
          val saveID = find(statesPerNode(idInColor) > i)
          val ids = idInColor(saveID)
          val pids = find(FMat(sum(pproject(ids, ?), 1)))
          initStateColor(kdata, ids, i, stateSet, user)
          computeP(ids, pids, i, pSet, pMatrix, stateSet(i), saveID, idInColor.length)
        }
        sampleColor(kdata, numState, idInColor, pSet, pMatrix, user)
      } 
      updateCPT(user)
    } 
  }
  
  /**
   * After one full round of Gibbs sampling iterations, we put in the local cpt, mm, into the updatemats(0)
   * value so that it gets "averaged into" the global cpt, modelmats(0). The reason for doing this is that
   * it is like thinning the samples so we pick every n-th one, where n is an opts parameter.
   * 
   * @param kdata A data matrix of this batch where a -1 indicates an unknown value, and {0,1,...,k} are
   *    the known values. Each row represents a random variable.
   * @param user Another data matrix with the same number of rows as kdata, and whose columns represent
   *    various iid assignments to all the variables. The known values of kdata are inserted in the same
   *    spots in this matrix, but the unknown values are appropriately randomized to be in {0,1,...,k}.
   * @param ipass The current pass over the full data source (not the Gibbs sampling iteration number).
   */
  def mupdate(kdata:Mat, user:Mat, ipass:Int):Unit = {
    println("In mupdate, mm(0 until 100).t=\n" + mm(0 until 100))
    updatemats(0) <-- mm
  } 
  
  /** 
   * Evaluates the log likelihood of this datasource segment (i.e., sdata). 
   * 
   * The sdata matrix has instances as columns, so one column is an assignment to variables. If each column
   * of sdata had a full assignment to all variables, then we easily compute log Pr(X1, ..., Xn) which is
   * the sum of elements in the CPT (i.e., the mm), sum(Xi | parents(Xi)). For the log-l of the full segment,
   * we need another addition around each column, so the full log-l is the sum of the log-l of each column.
   * This works because we assume each column is iid so we split up their joint probabilities. Taking logs 
   * then results in addition.
   * 
   * In reality, data is missing, and columns will not have a full assignment. That is where we incorporate
   * the user matrix to fill in the missing details. The user matrix fills in the unknown assignment values.
   * TODO Actually, since user contains all of sdata's known values, we DON'T NEED sdata, but we DO need to
   * make sure that "user" is correctly initialized...
   * 
   * @param sdata The data matrix, which may have missing assignments indicated by a -1.
   * @param user A matrix with a full assignment to all variables from sampling or randomization.
   * @return The log likelihood of the data.
   */
  def evalfun(sdata: Mat, user: Mat) : FMat = {
    val a = cptOffset + IMat(SMat(iproject) * FMat(user))
    val b = maxi(maxi(a,1),2).dv
    val index = IMat(cptOffset + SMat(iproject) * FMat(user))
    val ll = sum(sum(ln(getCpt(index))))
    return ll.dv
  }

  /**
   * Method to update the cpt table (i.e. mm). This method is called after we finish one iteration of Gibbs 
   * sampling. And this method only updates the local cpt table (mm), it has nothing to do with the learner's 
   * cpt parameter (which is modelmats(0)).
   * 
   * @param user The state matrix, updated after the sampling.
   */
  def updateCPT(user: Mat): Unit = {
    val numCols = size(user, 2)
    val index = IMat(cptOffset + SMat(iproject) * FMat(user))
    var counts = zeros(mm.length, 1)
    for (i <- 0 until numCols) {
      counts(index(?, i)) = counts(index(?, i)) + 1
    }
    counts = counts + opts.alpha  
    mm = (1 - opts.alpha) * mm + counts / (normConstMatrix * counts)
  }

  /**
   * Initializes the statei matrix for this particular color group and for this particular value. It
   * fills in the unknown values at the ids locations with i, then we can use it in computeP. I.e.,
   * the purpose of this is to make future probability computations/lookups efficient.
   * 
   * @param kdata Training data matrix, with unknowns of -1 and known values in {0,1,...,k}.
   * @param ids Indices of nodes in this color group that can also attain value/state i.
   * @param i An integer representing a value/state (we use these terms interchangeably).
   * @param stateSet An array of statei matrices, each of which has "i" in the unknowns of "ids".
   * @param user A data matrix with the same rows as kdata, and whose columns represent various iid
   *    assignments to all the variables. The known values of kdata are inserted in the same spots in
   *    this matrix, but the unknown values are appropriately randomized to be in {0,1,...,k}.
   */
  def initStateColor(kdata: Mat, ids: IMat, i: Int, stateSet: Array[Mat], user:Mat) = {
    var statei = user.copy
    statei(ids,?) = i
    val nonNegativeIndices = find(FMat(kdata) >= 0)
    for (i <- 0 until opts.nsampls) {
      statei(nonNegativeIndices + i*(kdata.nrows*kdata.ncols)) <-- kdata(nonNegativeIndices)
    }
    stateSet(i) = statei
  }

  /** 
   * Computes the un-normalized probability matrix for attaining a particular state. We also do
   * the cumulative sum for pMatrix so we can eventually use it as a normalizing constant.
   * 
   * @param ids Indices of nodes in this color group that can also attain value/state i.
   * @param pids Indices of nodes in "ids" AND the union of all the children of "ids" nodes.
   * @param i An integer representing a value/state (we use these terms interchangeably).
   * @param pSet The array of matrices, each of which represents probabilities of nodes attaining i.
   * @param pMatrix The matrix that represents normalizing constants for probabilities.
   * @param statei The matrix with unknown values at "ids" locations of "i".
   * @param saveID Indices of nodes in this color group that can attain i. (TODO Is this needed?)
   * @param numPi The number of nodes in the color group of "ids", including those that can't get i.
   */
  def computeP(ids: IMat, pids: IMat, i: Int, pSet: Array[Mat], pMatrix: Mat, statei: Mat, saveID: IMat, numPi: Int) = {
    val nodei = ln(getCpt(cptOffset(pids) + IMat(SMat(iproject(pids, ?)) * FMat(statei))) + opts.eps)
    var pii = zeros(numPi, statei.ncols)
    pii(saveID, ?) = exp(SMat(pproject(ids, pids)) * FMat(nodei))
    pSet(i) = pii
    pMatrix(saveID, ?) = pMatrix(saveID, ?) + pii(saveID, ?)
  }

  /** 
   * For a given color group, after we have gone through all its state possibilities, we sample it.
   * 
   * To start, we use a matrix of random values. Then, we go through each of the possible states and
   * if random values fall in a certain range, we assign the range's corresponding integer {0,1,...,k}.
   * 
   * @param fdata Training data matrix, with unknowns of -1 and known values in {0,1,...,k}.
   * @param numState The maximum number of state/values possible of any variable in this color group.
   * @param idInColor Indices of nodes in this color group.
   * @param pSet The array of matrices, each of which represents probabilities of nodes attaining i.
   * @param pMatrix The matrix that represents normalizing constants for probabilities.
   * @param user A matrix with a full assignment to all variables from sampling or randomization.
   */
  def sampleColor(kdata: Mat, numState: Int, idInColor: IMat, pSet: Array[Mat], pMatrix: Mat, user: Mat) = {
    val sampleMatrix = rand(idInColor.length, kdata.ncols * opts.nsampls)
    pSet(0) = pSet(0) / pMatrix
    //user(idInColor,?) <-- 0 * user(idInColor,?) // THIS IS NOT THE SAME!
    user(idInColor,?) = 0 * user(idInColor,?)
    
    // Each time, we check to make sure it's <= pSet(i), but ALSO exceeds the previous \sum (pSet(j)).
    for (i <- 1 until numState) {
      val saveID = find(statesPerNode(idInColor) > i)
      val ids = idInColor(saveID)
      val pids = find(FMat(sum(pproject(ids, ?), 1)))
      pSet(i) = (pSet(i) / pMatrix) + pSet(i-1) // Normalize and get the cumulative prob
      // Use Hadamard product to ensure that both requirements are held.
      user(ids, ?) = user(ids,?) + i * ((sampleMatrix(saveID, ?) <= pSet(i)(saveID, ?)) *@ (sampleMatrix(saveID, ?) >= pSet(i - 1)(saveID, ?)))
    }

    // Finally, re-write the known state into the state matrix
    val nonNegativeIndices = find(FMat(kdata) >= 0)
    for (j <- 0 until opts.nsampls) {
      user(nonNegativeIndices + j*(kdata.nrows*kdata.ncols)) = kdata(nonNegativeIndices)
    }
  }

  /**
   * Creates normalizing matrix N that we can then multiply with the cpt, i.e., N * cpt, to get a column
   * vector of the same length as the cpt, but such that cpt / (N * cpt) is normalized. Use SMat to save
   * on memory, I think.
   */
  def getNormConstMatrix(cpt: Mat) : SMat = {
    var ii = izeros(1,1)
    var jj = izeros(1,1)
    for (i <- 0 until graph.n-1) {
      var offset = cptOffset(i)
      val endOffset = cptOffset(i+1)
      val ns = statesPerNode(i)
      var indices = find2(ones(ns,ns))
      while (offset < endOffset) {
        ii = ii on (indices._1 + offset)
        jj = jj on (indices._2 + offset)
        offset = offset + ns
      }
    }
    var offsetLast = cptOffset(graph.n-1)
    var indices = find2(ones(statesPerNode(graph.n-1), statesPerNode(graph.n-1)))
    while (offsetLast < cpt.length) {
      ii = ii on (indices._1 + offsetLast)
      jj = jj on (indices._2 + offsetLast)
      offsetLast = offsetLast + statesPerNode(graph.n-1)
    }
    return sparse(ii(1 until ii.length), jj(1 until jj.length), ones(ii.length-1, 1), cpt.length, cpt.length)
  }

  /** Returns a conditional probability table specified by indices from the "index" matrix. */
  def getCpt(index: Mat) = {
    var cptindex = mm.zeros(index.nrows, index.ncols)
    for(i <-0 until index.ncols){
      cptindex(?, i) = mm(IMat(index(?, i)))
    }
    cptindex
  }

  /** Returns FALSE if there's an element at least size 2, which is BAD (well, only for binary data...). */
  def checkState(state: Mat) : Boolean = {
    val a = maxi(maxi(state,2),1).dv
    if (a >= 2) {
      return false
    }
    return true
  }

}



/**
 * There are three things the BayesNet needs as input:
 * 
 *  - A states per node array. Each value needs to be an integer that is at least two.
 *  - A DAG array, in which column i represents node i and its parents.
 *  - A sparse data matrix, where 0 indicates an unknown element, and rows are variables.
 * 
 * We don't need anything else for now, except for the number of gibbs iterations as input.
 */
object BayesNetMooc3  {
  
  trait Opts extends Model.Opts {
    var nsampls = 1
    var alpha = 1f
    var beta = 0.1f
    var samplingRate = 10
    var eps = 1e-9
    var numSamplesBurn = 20
  }
  
  class Options extends Opts {}

  /** 
   * A learner with a matrix data source, with states per node, and with a dag prepared. Call this
   * using (assuming proper names):
   * 
   * val (nn,opts) = BayesNetMooc3.learner(loadIMat("states.lz4"), loadSMat("dag.lz4"), loadSMat("sdata.lz4"))
   */
  def learner(statesPerNode:Mat, dag:Mat, data:Mat) = {

    class xopts extends Learner.Options with BayesNetMooc3.Opts with MatDS.Opts with IncNorm.Opts 
    val opts = new xopts
    opts.dim = dag.ncols
    opts.batchSize = 1000
    opts.useGPU = false     // Temporary TODO test with GPUs, delete this line later
    opts.updateAll = true   // Temporary TODO (this means that we do the same IncNorm update even with "evalfun"
    //opts.power = 1f         // TODO John suggested that we do not need 1f here
    opts.isprob = false     // Because our cpts should NOT be normalized across their one column (lol).
    opts.putBack = 1        // Temporary TODO because we will assume we have npasses = 1 for now
    opts.npasses = 1        // Temporary TODO because I would like to get one pass working correctly.

    val nn = new Learner(
        new MatDS(Array(data:Mat), opts),
        new BayesNetMooc3(dag, statesPerNode, opts),
        null,
        new IncNorm(opts),
        opts)
    (nn, opts)
  }
}



/**
 * A graph structure for Bayesian Networks. Includes features for:
 * 
 *   (1) moralizing graphs, 'moral' matrix must be (i,j) = 1 means node i is connected to node j
 *   (2) coloring moralized graphs, not sure why there is a maxColor here, though...
 *
 * @param dag An adjacency matrix with a 1 at (i,j) if node i has an edge TOWARDS node j.
 * @param n The number of vertices in the graph. 
 * @param statesPerNode A column vector where elements denote number of states for corresponding variables.
 */
// TODO making this Graph1 instead of Graph so that we don't get conflicts
// TODO investigate all non-Mat matrices (IMat, FMat, etc.) to see if these can be Mats to make them usable on GPUs
// TODO Also see if connectParents, moralize, and color can be converted to GPU friendly code
class Graph1(val dag: SMat, val n: Int, val statesPerNode: IMat) {
 
  var mrf: FMat = null
  var colors: IMat = null
  var ncolors = 0
  val maxColor = 100
   
  /**
   * Connects the parents of a certain node, a single step in the process of moralizing the graph.
   * 
   * Iterates through the parent indices and insert 1s in the 'moral' matrix to indicate an edge.
   * 
   * @param moral A matrix that represents an adjacency matrix "in progress" in the sense that it
   *    is continually getting updated each iteration from the "moralize" method.
   * @param parents An array representing the parent indices of the node of interest.
   */
  def connectParents(moral: FMat, parents: IMat) = {
    val l = parents.length
    for(i <- 0 until l)
      for(j <- 0 until l){
        if(parents(i) != parents(j)){
          moral(parents(i), parents(j)) = 1f
        }
      }
    moral
  } 

  /** Forms the pproject matrix (graph + identity) used for computing model parameters. */
  def pproject = {
    dag + sparse(IMat(0 until n), IMat(0 until n), ones(1, n))
  }
  
  /**
   * Forms the iproject matrix, which is left-multiplied to send a Pr(X_i | parents) query to its
   * appropriate spot in the cpt via LOCAL offsets for X_i.
   */
  def iproject = {
    var res = (pproject.copy).t
    for (i <- 0 until n) {
      val parents = find(pproject(?, i))
      var cumRes = 1
      val parentsLen = parents.length
      for (j <- 1 until parentsLen) {
        cumRes = cumRes * statesPerNode(parents(parentsLen - j))
        res(i, parents(parentsLen - j - 1)) = cumRes.toFloat
      }
    }
    res
  }
  
  /**
   * Moralize the graph.
   * 
   * This means we convert the graph from directed to undirected and connect parents of nodes in 
   * the directed graph. First, copy the dag to the moral graph because all 1s in the dag matrix
   * are 1s in the moral matrix (these are adjacency matrices). For each node, find its parents,
   * connect them, and update the matrix. Then make it symmetric because the graph is undirected.
   */
  def moralize = {
    var moral = full(dag)
    for (i <- 0 until n) {
      var parents = find(dag(?, i))
      moral = connectParents(moral, parents)
    }
    mrf = ((moral + moral.t) > 0)
  }
  
  /**
   * Sequentially colors the moralized graph of the dag so that one can run parallel Gibbs sampling.
   * 
   * Steps: first, moralize the graph. Then iterate through each node, find its neighbors, and apply a
   * "color mask" to ensure current node doesn't have any of those colors. Then find the legal color
   * with least count (a useful heuristic). If that's not possible, then increase "ncolor".
   */
  def color = {
    moralize
    var colorCount = izeros(maxColor, 1)
    colors = -1 * iones(n, 1)
    ncolors = 0
   
    // Access nodes sequentially. Find the color map of its neighbors, then find the legal color w/least count
    val seq = IMat(0 until n)
    // Can also access nodes randomly
    // val r = rand(n, 1); val (v, seq) = sort2(r)

    for (i <- 0 until n) {
      var node = seq(i)
      var nbs = find(mrf(?, node))
      var colorMap = iones(ncolors, 1)
      for (j <- 0 until nbs.length) {
        if (colors(nbs(j)) > -1) {
          colorMap(colors(nbs(j))) = 0
        }
      }
      var c = -1
      var minc = 999999
      for (k <- 0 until ncolors) {
        if ((colorMap(k) > 0) && (colorCount(k) < minc)) {
          c = k
          minc = colorCount(k)
        }
      }
      if (c == -1) {
       c = ncolors
       ncolors = ncolors + 1
      }
      colors(node) = c
      colorCount(c) += 1
    }
    colors
  }
 
}
