package ch.epfl.ts.component.fetch

import ch.epfl.ts.data.Currency._
import ch.epfl.ts.data.{ Transaction, LimitOrder, LimitAskOrder, LimitBidOrder, Order, LiveLimitAskOrder, LiveLimitBidOrder, DelOrder }
import net.liftweb.json._
import org.apache.http.client.fluent._

class BitstampTransactionPullFetcher extends PullFetch[Transaction] {
  val bitstamp = new BitstampAPI(USD, BTC)
  var count = 100
  var latest = new Transaction(MarketNames.BITSTAMP_ID, 0.0, 0.0, 0, BTC, USD, 0, 0, 0, 0)

  override def interval(): Int = 12000

  override def fetch(): List[Transaction] = {
    val trades = bitstamp.getTrade(count)

    val idx = trades.indexOf(latest)
    count = if (idx < 0) 100 else Math.min(10 * idx, 100)
    latest = if (trades.length == 0) latest else trades.head

    if (idx > 0)
      trades.slice(0, idx).reverse
    else
      trades.reverse
  }
}

class BitstampOrderPullFetcher extends PullFetch[Order] {
  val bitstampApi = new BitstampAPI(USD, BTC)
  var count = 2000
  override def interval(): Int = 12000
  var oldOrders = Map[LimitOrder, Long]()
  var oid = 5000000000L
  override def fetch(): List[Order] = {
    // acquire currently active orders
    val currentOrders = bitstampApi.getDepth(count)
    println("BitstampOrderPullFetcher: current orders size: " + currentOrders.size)
    // find which are new by computing the difference: newOrders = currentOrders - oldOrders
    val newOrders: List[LimitOrder] = currentOrders.filterNot(oldOrders.keySet)
    println("BitstampOrderPullFetcher: new Orders size: " + newOrders.size + ", should be currentOrders - oldOrders - deletedOrders.")
    // assign oid to new orders & add new orders to currently active orders (will be old orders in the next iteration)
    val newOrdersWithId: List[LimitOrder] = newOrders.map { x =>
      x match {
        case lb: LiveLimitBidOrder =>
          oid = oid + 1
          oldOrders += (x -> oid)
          LiveLimitBidOrder(oid, x.uid, x.timestamp, x.whatC, x.withC, x.volume, x.price)
        case la: LiveLimitAskOrder =>
          oid = oid + 1
          oldOrders += (x -> oid)
          LiveLimitAskOrder(oid, x.uid, x.timestamp, x.whatC, x.withC, x.volume, x.price)
      }
    }

    // find which were deleted (or executed) by computing the difference: deletedOrders = oldOrders - currentOrders
    val deletedOrders: List[LimitOrder] = oldOrders.keySet.filterNot(currentOrders.toSet).toList
    println("BitstampOrderPullFetcher: deletedOrders size: " + deletedOrders.size + ", should be oldOrders - currentOrders - newOrders")

    // convert deleted orders into DelOrders
    val delOrders: List[DelOrder] = deletedOrders.map { x => DelOrder(oldOrders(x), x.uid, x.timestamp, x.whatC, x.withC, x.volume, x.price) }

    // add new orders to currently active orders (will be old orders in the next iteration)
    // remove deleted orders from currently active orders (will be old orders in the next iteration)
    deletedOrders.map { x => oldOrders -= x }
    println("BitstampOrderPullFetcher: updated old orders size: " + oldOrders.size + ", should be same as currentOrders")

    newOrdersWithId ::: delOrders
  }
}

case class BitstampCaseTransaction(date: String, tid: Int, price: String, amount: String)

case class BitstampDepth(timestamp: String, bids: List[List[String]], asks: List[List[String]])

class BitstampAPI(from: Currency, to: Currency) {
  implicit val formats = net.liftweb.json.DefaultFormats

  val serverBase = "https://www.bitstamp.net/api/"

  def getInfo {}

  def getTicker {}

  def getTrade(count: Int): List[Transaction] = {
    val path = serverBase + "transactions/"
    val json = Request.Get(path).execute().returnContent().asString()
    val t = parse(json).extract[List[BitstampCaseTransaction]]

    t.map(f => new Transaction(MarketNames.BITSTAMP_ID, f.price.toDouble, f.amount.toDouble, f.date.toLong * 1000, BTC, USD, 0, 0, 0, 0))
  }

  def getDepth(count: Int): List[LimitOrder] = {
    var t = List[LimitOrder]()
    try {
      val path = serverBase + "order_book/"
      val json = Request.Get(path).execute().returnContent().asString()
      val a = parse(json).extract[BitstampDepth]
      println("asks: " + a.asks.size)

      val asks = a.asks.map { e => LiveLimitAskOrder(0, 0, 0L, USD, BTC, e.last.toDouble, e.head.toDouble) }

      val bids = a.bids.map { e => LiveLimitBidOrder(0, 0, 0L, USD, BTC, e.last.toDouble, e.head.toDouble) }

      t = asks ++ bids
    } catch {
      case e: Throwable => {
        println("error: " + e)
        t = List[LimitOrder]()
      }
    }
    t
  }
}