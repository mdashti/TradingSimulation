package ch.epfl.ts.optimization

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala
import ch.epfl.ts.component.ComponentBuilder
import ch.epfl.ts.component.StartSignal
import ch.epfl.ts.data.Currency
import ch.epfl.ts.data.CurrencyPairParameter
import ch.epfl.ts.data.EndOfFetching
import ch.epfl.ts.data.MarketAskOrder
import ch.epfl.ts.data.MarketBidOrder
import ch.epfl.ts.data.Quote
import ch.epfl.ts.data.RealNumberParameter
import ch.epfl.ts.data.Register
import ch.epfl.ts.data.TimeParameter
import ch.epfl.ts.data.Transaction
import ch.epfl.ts.data.WalletParameter
import ch.epfl.ts.engine.ExecutedAskOrder
import ch.epfl.ts.engine.ExecutedBidOrder
import ch.epfl.ts.engine.FundWallet
import ch.epfl.ts.engine.GetWalletFunds
import ch.epfl.ts.engine.Wallet
import ch.epfl.ts.evaluation.EvaluationReport
import ch.epfl.ts.traders.MovingAverageTrader
import ch.epfl.ts.data.LimitAskOrder
import ch.epfl.ts.data.LimitBidOrder

/**
 * Runs a main() method that creates all remote systems
 * as well as an `OptimizationSupervisor` to watch them all.
 * It assumes that there is a `RemotingWorker` class running and listening
 * on every availableWorker.
 *
 * @see {@link ch.epfl.ts.optimization.RemotingWorker}
 */
object RemotingMasterRunner {

  val availableHosts = {
    val availableWorkers = List(
      "localhost"
      //"ts-1-021qv44y.cloudapp.net",
      //"ts-2.cloudapp.net"
      //"ts-3.cloudapp.net",
      //"ts-4.cloudapp.net",
      //"ts-5.cloudapp.net",
      //"ts-6.cloudapp.net",
      //"ts-7.cloudapp.net",
      //"ts-8.cloudapp.net"
    )
    val port = 3333
    // TODO: lookup in configuration
    val systemName = "remote"

    availableWorkers.map(hostname => {
      val prefix = hostname.substring(0, 4)
      new RemoteHost(hostname, port, prefix, systemName)
    })
  }

  /**
   * Use this if we need to terminate early regardless of the data being fetched
   */
  def terminateOptimizationAfter(delay: FiniteDuration, supervisor: ActorRef)(implicit builder: ComponentBuilder) =
    builder.system.scheduler.scheduleOnce(delay) {
      println("---------- Terminating optimization after a fixed duration of " + delay)
      supervisor ! EndOfFetching(System.currentTimeMillis())
    }


  def main(args: Array[String]): Unit = {

    implicit val builder = new ComponentBuilder()

    // ----- Supervisor actor
    val master = builder.createRef(Props(classOf[OptimizationSupervisor]), "MasterActor")

    // ----- Factory: class responsible for creating the components
    val speed = 200000.0
    val symbol = (Currency.EUR, Currency.CHF)
    val start = "201304"
    val end = "201304"
    val factory = new ForexReplayStrategyFactory(10 seconds, symbol._2, symbol, speed, start, end)


    // ----- Generate candidate parameterizations
    val strategyToOptimize = MovingAverageTrader
    val parametersToOptimize = Set(
      MovingAverageTrader.SHORT_PERIODS,
      MovingAverageTrader.LONG_PERIODS
    )
    val initialWallet: Wallet.Type = Map(Currency.EUR -> 0, Currency.CHF -> 5000.0)
    val otherParameterValues = Map(
      MovingAverageTrader.INITIAL_FUNDS -> WalletParameter(initialWallet),
      MovingAverageTrader.SYMBOL -> CurrencyPairParameter(symbol),
      MovingAverageTrader.OHLC_PERIOD -> new TimeParameter(1 day),
      MovingAverageTrader.TOLERANCE -> RealNumberParameter(0.0002)
    )

    val maxInstances = (100 * availableHosts.size)
    val parameterizations = StrategyOptimizer.generateParameterizations(strategyToOptimize, parametersToOptimize,
                                                                        otherParameterValues, maxInstances).toSet

    // TODO: use log.info
    println("Going to distribute " + parameterizations.size + " traders over " + availableHosts.size + " worker machines.")

    // ----- Instantiate all relevant components on each available worker
    val distributed = factory.distributeOverHosts(availableHosts, parameterizations)
    val deployments = distributed.map({ case (host, parameters) =>
      println("Creating " + parameters.size + " instances of " + strategyToOptimize.getClass.getSimpleName + " on host " + host)
      factory.createRemoteActors(master, host, strategyToOptimize, parameterizations)
    })

    // ----- Connections
    deployments.foreach(d => {
      d.fetcher -> (d.market, classOf[Quote])
      d.fetcher -> (Seq(d.market, master), classOf[EndOfFetching])
      d.market -> (d.broker, classOf[Quote], classOf[ExecutedBidOrder], classOf[ExecutedAskOrder])
      // TODO: make sure to support all order types
      d.broker -> (d.market, classOf[LimitBidOrder], classOf[LimitAskOrder], classOf[MarketBidOrder], classOf[MarketAskOrder])

      for(e <- d.evaluators) {
        d.fetcher -> (e, classOf[EndOfFetching])
        e -> (d.broker, classOf[Register], classOf[FundWallet], classOf[GetWalletFunds])
        e -> (d.broker, classOf[LimitBidOrder], classOf[LimitAskOrder], classOf[MarketBidOrder], classOf[MarketAskOrder])
        d.market -> (e, classOf[Quote], classOf[ExecutedBidOrder], classOf[ExecutedAskOrder])
        d.market -> (e, classOf[Transaction])

        e -> (master, classOf[EvaluationReport])
      }

      for(printer <- d.printer) {
        d.market -> (printer, classOf[Transaction])
        d.fetcher -> (printer, classOf[EndOfFetching])

        for(e <- d.evaluators) e -> (printer, classOf[EvaluationReport])
      }
    })

    // Make sure brokers are started before the traders
    master.ar ! StartSignal
    for(d <- deployments) d.broker.ar ! StartSignal
    builder.start

    // ----- Registration to the supervisor
    // Register each new trader to the master
    for(d <- deployments; e <- d.evaluators) {
      master.ar ! e.ar
    }

    // Use this if we need to terminate early regardless of the data being fetched
    //terminateOptimizationAfter(11 seconds, master.ar)

    // TODO: fix actor names including the full path to the host system (even though it is actually created in the remote system)
  }
}
