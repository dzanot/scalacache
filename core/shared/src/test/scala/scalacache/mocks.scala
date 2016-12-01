package scalacache

import scala.collection.mutable.ArrayBuffer
import scalacache.serialization.{ Codec, InMemoryRepr }

import com.twitter.util.{ Duration, Future }

class EmptyCache extends Cache[InMemoryRepr] {
  override def get[V](key: String)(implicit codec: Codec[V, InMemoryRepr]): Future[Option[V]] = Future.value(None)
  override def put[V](key: String, value: V, ttl: Option[Duration])(implicit codec: Codec[V, InMemoryRepr]) = Future.value((): Unit)
  override def remove(key: String) = Future.value((): Unit)
  override def removeAll() = Future.value((): Unit)
  override def close(): Unit = {}
}

class FullCache(value: Any) extends Cache[InMemoryRepr] {
  override def get[V](key: String)(implicit codec: Codec[V, InMemoryRepr]): Future[Option[V]] = Future.value(Some(value).asInstanceOf[Option[V]])
  override def put[V](key: String, value: V, ttl: Option[Duration])(implicit codec: Codec[V, InMemoryRepr]) = Future.value((): Unit)
  override def remove(key: String) = Future.value((): Unit)
  override def removeAll() = Future.value((): Unit)
  override def close(): Unit = {}
}

class FailedFutureReturningCache extends Cache[InMemoryRepr] {
  override def get[V](key: String)(implicit codec: Codec[V, InMemoryRepr]): Future[Option[V]] = Future.value(throw new RuntimeException("failed to read"))
  override def put[V](key: String, value: V, ttl: Option[Duration])(implicit codec: Codec[V, InMemoryRepr]): Future[Unit] = Future.value(throw new RuntimeException("failed to write"))
  override def remove(key: String) = Future.value((): Unit)
  override def removeAll() = Future.value((): Unit)
  override def close(): Unit = {}
}

/**
 * A mock cache for use in tests and samples.
 * Does not support TTL.
 */
class MockCache extends Cache[InMemoryRepr] {

  val mmap = collection.mutable.Map[String, Any]()

  def get[V](key: String)(implicit codec: Codec[V, InMemoryRepr]) = {
    val value = mmap.get(key)
    Future.value(value.asInstanceOf[Option[V]])
  }

  def put[V](key: String, value: V, ttl: Option[Duration])(implicit codec: Codec[V, InMemoryRepr]) =
    Future.value(mmap.put(key, value))

  def remove(key: String) =
    Future.value(mmap.remove(key))

  def removeAll() =
    Future.value(mmap.clear())

  def close(): Unit = {}

}

/**
 * A cache that keeps track of the arguments it was called with. Useful for tests.
 * Designed to be mixed in as a stackable trait.
 */
trait LoggingCache extends Cache[InMemoryRepr] {
  var (getCalledWithArgs, putCalledWithArgs, removeCalledWithArgs) = (
    ArrayBuffer.empty[String],
    ArrayBuffer.empty[(String, Any, Option[Duration])],
    ArrayBuffer.empty[String])

  abstract override def get[V](key: String)(implicit codec: Codec[V, InMemoryRepr]): Future[Option[V]] = {
    getCalledWithArgs.append(key)
    super.get[V](key)
  }

  abstract override def put[V](key: String, value: V, ttl: Option[Duration])(implicit codec: Codec[V, InMemoryRepr]) = {
    putCalledWithArgs.append((key, value, ttl))
    super.put(key, value, ttl)
  }

  abstract override def remove(key: String) = {
    removeCalledWithArgs.append(key)
    super.remove(key)
  }

  def reset(): Unit = {
    getCalledWithArgs.clear()
    putCalledWithArgs.clear()
    removeCalledWithArgs.clear()
  }

}

/**
 * A mock cache that keeps track of the arguments it was called with.
 */
class LoggingMockCache extends MockCache with LoggingCache
