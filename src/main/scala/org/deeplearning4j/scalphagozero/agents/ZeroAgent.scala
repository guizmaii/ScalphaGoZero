package org.deeplearning4j.scalphagozero.agents
import org.deeplearning4j.nn.graph.ComputationGraph
import org.deeplearning4j.scalphagozero.board.{ GameState, Move }
import org.deeplearning4j.scalphagozero.encoders.ZeroEncoder
import org.deeplearning4j.scalphagozero.experience.{ ZeroExperienceBuffer, ZeroExperienceCollector }
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.factory.Nd4j

import scala.collection.mutable

/**
  * AlphaGo Zero agent, main workhorse of this project. ZeroAgent implements the characteristic combination of
  * tree search with reinforcement learning that lead to breakthrough results for the game of Go (AlphaGo Zero)
  * and other board games like chess (Alpha Zero).
  *
  * @param model DL4J computation graph suitable for AGZ predictions
  * @param encoder ZeroEncoder instance to feed data into the model
  * @param roundsPerMove roll-outs per move
  * @param c constant to multiply score with (defaults to 2.0)
  *
  * @author Max Pumperla
  */
class ZeroAgent(val model: ComputationGraph, val encoder: ZeroEncoder, val roundsPerMove: Int = 10, val c: Double = 2.0)
    extends Agent {

  val collector: ZeroExperienceCollector = new ZeroExperienceCollector()

  override def selectMove(gameState: GameState): Move = {
    val root = createNode(gameState, None, None)
    for (i <- 0 until roundsPerMove) {
      var node: Option[ZeroTreeNode] = Some(root)
      var nextMove = selectBranch(node.get)
      while (node.get.hasChild(nextMove)) {
        node = node.get.getChild(nextMove)
        nextMove = selectBranch(node.get)
      }
      val newState = node.get.gameState.applyMove(nextMove)
      val childNode = createNode(newState, Some(nextMove), node)
      var move: Option[Move] = Some(nextMove)
      var value = -1 * childNode.value
      while (node.isDefined && move.isDefined) {
        node.get.recordVisit(move.get, value)
        move = node.get.lastMove
        value = -1 * value
      }
    }
    val rootStateTensor = encoder.encode(gameState)
    val visitCounts: INDArray = Nd4j.create(1, encoder.numMoves())
    for (index <- 0 until encoder.numMoves()) {
      val move: Move = encoder.decodeMoveIndex(index)
      visitCounts.put(1, index, Nd4j.scalar(root.visitCount(move)))
    }
    collector.recordDecision(rootStateTensor, visitCounts)

    val validMoves = root.moves.filter(m => gameState.isValidMove(m))
    validMoves.map(m => (m, root.visitCount(m))).toMap.maxBy(_._2)._1
  }

  /**
    * Select a move given a node.
    *
    * @param node ZeroTreeNode
    * @return Move instance
    */
  def selectBranch(node: ZeroTreeNode): Move = {
    val totalCount = node.totalVisitCount

    def scoreBranch(move: Move): Double = {
      val q = node.expectedValue(move)
      val p = node.prior(move)
      val n = node.visitCount(move)
      q + this.c * p * Math.sqrt(totalCount) / (n + 1)
    }

    node.moves
      .map(m => (m, scoreBranch(m)))
      .reduce((m1, m2) => if (m1._2 > m2._2) m1 else m2)
      ._1
  }

  /**
    * Create a new ZeroTreeNode from the current game state.
    *
    * @param gameState game state
    * @param move optional move
    * @param parent optional parent ZeroTreeNode
    * @return ZeroTreeNode
    */
  def createNode(gameState: GameState, move: Option[Move], parent: Option[ZeroTreeNode]): ZeroTreeNode = {
    val stateTensor: INDArray = this.encoder.encode(gameState)
    val outputs = this.model.output(stateTensor)
    val priors = outputs(0)
    val value = outputs(1).getDouble(0L, 0L)

    val movePriors: mutable.Map[Move, Double] = new mutable.HashMap[Move, Double]()
    for (i <- 0 until priors.length().toInt) {
      val move = encoder.decodeMoveIndex(i.toInt)
      val prior = priors.getDouble(i.toLong)
      movePriors.put(move, prior)
    }

    val newNode = new ZeroTreeNode(gameState, value, movePriors, parent, move)
    if (parent.isDefined && move.isDefined) {
      parent.get.addChild(move.get, newNode)
    }
    newNode
  }

  /**
    * Learn from experience, after the play-out is done.
    *
    * @param experience ZeroExperienceBuffer
    */
  def train(experience: ZeroExperienceBuffer): Unit = {
    val numExamples = experience.states.shape()(0).toInt

    val modelInput: INDArray = experience.states

    val countLength = experience.visitCounts.shape()(1)
    val visitSums = Nd4j.sum(experience.visitCounts, 1).reshape(Array[Int](numExamples, 1))
    val actionTarget = experience.visitCounts.div(visitSums.repeat(1, countLength))
    val valueTarget = experience.rewards

    model.fit(Array[INDArray](modelInput), Array[INDArray](actionTarget, valueTarget))
  }

}
