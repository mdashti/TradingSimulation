package ch.epfl.ts.test

import scala.language.postfixOps
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.WordSpecLike

import com.typesafe.config.ConfigFactory

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.actorRef2Scala
import akka.testkit.TestKit
import akka.util.Timeout
import ch.epfl.ts.brokers.StandardBroker
import ch.epfl.ts.component.ComponentBuilder
import scala.concurrent.Await
import ch.epfl.ts.engine.rules.FxMarketRulesWrapper
import ch.epfl.ts.engine.{MarketFXSimulator, ForexMarketRules}


object TestHelpers {
  def makeTestActorSystem(name: String = "TestActorSystem") =
    ActorSystem(name, ConfigFactory.parseString(
      """
      akka.loglevel = "DEBUG"
      akka.loggers = ["akka.testkit.TestEventListener"]
      """
    ).withFallback(ConfigFactory.load()))
}

/**
 * Common superclass for testing actors
 * @param name Name of the actor system
 */
abstract class ActorTestSuite(val name: String)
  extends TestKit(TestHelpers.makeTestActorSystem(name))
  with WordSpecLike
  with BeforeAndAfterAll
  with BeforeAndAfterEach {
  
  implicit val builder = new ComponentBuilder(system)
  
  val shutdownTimeout = 3 seconds
  
	/** After all tests have run, shut down the system */
  override def afterAll() = {
    TestKit.shutdownActorSystem(system, shutdownTimeout)
  }
  
}

/**
 * A bit dirty hack to allow ComponentRef-like communication between components, while having them in Test ActorSystem
 */
class SimpleBrokerWrapped(market: ActorRef) extends StandardBroker {
  override def send[T: ClassTag](t: T) {
    market ! t
  }

  override def send[T: ClassTag](t: List[T]) = t.map(market ! _)
}

/**
 * A bit dirty hack to allow ComponentRef-like communication between components, while having them in Test ActorSystem
 */
class FxMarketWrapped(uid: Long, rules: ForexMarketRules) extends MarketFXSimulator(uid, new FxMarketRulesWrapper(rules)) {
  import context.dispatcher
  override def send[T: ClassTag](t: T) {
    val brokerSelection = context.actorSelection("/user/brokers/*")
    implicit val timeout = new Timeout(100 milliseconds)
    val broker = Await.result(brokerSelection.resolveOne(), timeout.duration)
    println("Tried to get Broker: " + broker)
    println("Market sent to Broker ONLY: " + t)
    broker ! t
  }
}
