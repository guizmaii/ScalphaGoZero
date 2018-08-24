package org.deeplearning4j.scalphagozero.encoders
import org.deeplearning4j.scalphagozero.board._
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.factory.Nd4j

/**
  * AlphaGo Zero Go board encoder, an eleven-plane encoder structured as follows:
  *
  * Planes 0 - 3: Our stones with 1, 2, 3 and 4+ liberties
  * Planes 4 - 7: Opponent stones with 1, 2, 3 and 4+ liberties
  * Plane      8: All ones if we get Komi
  * Plane      9: All ones if opponent gets Komi
  * Plane     10: Indicates moves illegal due to Ko
  *
  * @author Max Pumperla
  */
final class ZeroEncoder(boardHeight: Int, boardWidth: Int) extends Encoder(boardHeight, boardWidth, 11) {

  override val name: String = "AlphaGoZero"

  val boardSize = boardWidth * boardHeight
  val numMoves: Int = boardSize + 1

  /**
    * Encode the current game state as board tensor
    *
    * @param gameState GameState instance
    * @return Board tensor representation of the game state
    */
  override def encode(gameState: GameState): INDArray = {

    val tensor = Nd4j.zeros(this.shape: _*)

    val nextPlayer: Player = gameState.nextPlayer
    nextPlayer.color match {
      case PlayerColor.White => tensor.putSlice(8, Nd4j.ones(boardHeight, boardWidth));
      case PlayerColor.Black => tensor.putSlice(9, Nd4j.ones(boardHeight, boardWidth));
    }
    for (row <- 0 until this.boardHeight) {
      for (col <- 0 until this.boardWidth) {
        val p = Point(row + 1, col + 1)
        val goString: Option[GoString] = gameState.board.getGoString(p)

        goString match {
          case None =>
            if (gameState.doesMoveViolateKo(nextPlayer, Move.Play(p)))
              tensor.put(Array(10, row, col), Nd4j.scalar(1))
          case Some(string) =>
            var libertyPlane = Math.max(Math.min(4, string.numLiberties) - 1, 1)
            if (string.color.equals(nextPlayer.color))
              libertyPlane += 4
            tensor.put(Array(libertyPlane, row, col), Nd4j.scalar(1))
        }
      }
    }
    val shape = tensor.shape()
    val batchTensor = tensor.reshape(1, shape(0), shape(1), shape(2))
    batchTensor
  }

  override def encodeMove(move: Move): Int =
    move match {
      case Move.Play(point) => boardHeight * (point.row - 1) + (point.col - 1)
      case Move.Pass        => boardSize
      case _                => throw new IllegalArgumentException("Cannot encode resign move")
    }

  override def decodeMoveIndex(index: Int): Move =
    if (index == boardSize) {
      Move.Pass
    } else {
      val row = index / boardHeight
      val col = index % boardHeight
      Move.Play(Point(row + 1, col + 1))
    }

}

object ZeroEncoder {

  def apply(): ZeroEncoder = new ZeroEncoder(19, 19)

  def apply(boardHeight: Int, boardWidth: Int): ZeroEncoder =
    new ZeroEncoder(boardHeight, boardWidth)
}
