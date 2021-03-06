package ch.epfl.ts.engine

import akka.actor.{ ActorLogging, Actor }
import ch.epfl.ts.data.Currency

/**
 * Wallet actor's companion object
 */
object Wallet {
  /** Data representation of the funds in multiple currencies */
  type Type = Map[Currency, Double]
}

/*
* Represents an one trader's wallet
* TODO(sygi): remove user id from those communicates (ch.epfl.ts.engine.Messages)
*/
class Wallet extends Actor with ActorLogging {
  var funds: Wallet.Type = Map[Currency, Double]()

  override def receive = {
    case GetWalletFunds(uid, ref)    => answerGetWalletFunds(uid)
    case FundWallet(uid, c, q, aneg) => fundWallet(uid, c, q, aneg)
  }

  def answerGetWalletFunds(uid: Long): Unit = {
    sender ! WalletFunds(uid, funds)
  }

  /**
   * Method to add given amount of currency to the wallet. Negative amount corresponds to charging a wallet.
   * Will return success/failure message to the sender.
   * @param uid - id of a wallet, will probably be removed at some point
   * @param c - currency name
   * @param q - amount to be added to the wallet.
   * @param allowNegative - Allow negative amount of money (for example in case of short orders)
   *
   */
  def fundWallet(uid: Long, c: Currency, q: Double, allowNegative: Boolean): Unit = {
    funds.get(c) match {
      case None => {
        log.debug("adding a new currency")
        funds = funds + (c -> 0.0)
        fundWallet(uid, c, q, allowNegative)
      }
      case Some(status) => {
        log.debug("adding " + q + " to currency " + c)
        if (q + status >= 0.0 || allowNegative) {
          funds = funds + (c -> (q + status))
          log.debug("Confirming")
          sender ! WalletConfirm(uid)
        } else
          sender ! WalletInsufficient(uid)
      }
    }
  }
}
