package ch.epfl.ts.brokers

import akka.actor.{ActorLogging, Props, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import ch.epfl.ts.component.Component
import scala.concurrent.duration._
import scala.language.postfixOps
import ch.epfl.ts.engine.{Wallet, WalletConfirm, FundWallet, WalletFunds,
  GetWalletFunds,WalletInsufficient, ExecutedOrder, WalletState, AcceptedOrder, RejectedOrder}
import scala.Some
import ch.epfl.ts.data.{Register, ConfirmRegistration, Order}

/**
 * Created by sygi on 03.04.15.
 */
class ExampleBroker extends Component with ActorLogging {
  import context.dispatcher
  var mapping = Map[Long, ActorRef]()
  val dummyReturn: PartialFunction[Any, Unit] = {case _ => {}}
  override def receiver: PartialFunction[Any, Unit] = {
    case Register(id) => {
      log.debug("Broker: registration of agent " + id)
      log.debug("with ref: " + sender())
      if (mapping.get(id) != None){
        log.debug("Duplicate Id")
        //TODO(sygi): reply to the trader that registration failed
        return dummyReturn
      }
      mapping = mapping + (id -> sender())
      context.actorOf(Props[Wallet], "wallet" + id)
      sender() ! ConfirmRegistration
    }
    case FundWallet(uid, curr, value) => {
      log.debug("Broker: got a request to fund a wallet")
      val replyTo = sender
      executeForWallet(uid, FundWallet(uid, curr, value), {
        case WalletConfirm(uid) => {
          log.debug("Broker: Wallet confirmed")
          replyTo ! WalletConfirm(uid)
        }
        case WalletInsufficient(uid) => {
          log.debug("Broker: insufficient funds")
          replyTo ! WalletInsufficient(uid)
        }
      })
    }
    case GetWalletFunds(uid) => {
      log.debug("Broker: got a get show wallet request")
      val replyTo = sender
      if (mapping.get(uid) != Some(replyTo)) {
        log.debug("Broker: someone asks for not - his wallet")
        return dummyReturn
      }
      executeForWallet(uid, GetWalletFunds(uid), {
        case w: WalletFunds => {
          replyTo ! w
        }
      })
    }

    case e: ExecutedOrder => {
      if (mapping.contains(e.uid)){
        val replyTo = mapping.getOrElse(e.uid, null)
        executeForWallet(e.uid, FundWallet(e.uid, e.whatC, e.volume / e.price), {
          case WalletConfirm(uid) => {
            log.debug("Broker: Transaction executed")
            replyTo ! e
          }
          case p => log.debug("Broker: A wallet replied with an unexpected message: " + p)
        })
      }
    }

    //TODO(sygi): refactor charging the wallet/placing an order
    case o: Order => {
      log.debug("Broker: received order")
      val replyTo = sender
      val uid = o.chargedTraderId()
      val placementCost = o.costValue()
      val costCurrency = o.costCurrency()
      executeForWallet(uid, FundWallet(uid, costCurrency, -placementCost), {
        case WalletConfirm(uid) => {
          log.debug("Broker: Wallet confirmed")
          send(o)
          replyTo ! AcceptedOrder.apply(o) //means: order placed
        }
        case WalletInsufficient(uid) => {
          replyTo ! RejectedOrder.apply(o)
          log.debug("Broker: insufficient funds")
        }
        case _ => log.debug("Unexpected message")
      })
    }

    case p => log.debug("Broker: received unknown " + p)
  }

  //TODO(sygi) - implement it
  //def addToWallet(uid: Long, currency: Currency, amount: Double, messageOnSuccess: Option[Any], messageOnFailure: Option[Any])

  def executeForWallet(uid: Long, question: WalletState, cb: PartialFunction[Any, Unit]) = { 
    context.child("wallet" + uid) match {
      case Some(walletActor) => {
        implicit val timeout = new Timeout(100 milliseconds)
        val future = (walletActor ? question).mapTo[WalletState]
        future onSuccess cb
        future onFailure {
          case p => log.debug("Broker: Wallet command failed: " + p)
        }
      }
      case None => log.debug("Broker: No such wallet")
    }
  }
}