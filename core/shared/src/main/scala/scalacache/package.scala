
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import scalacache.serialization.{ Codec, JavaSerializationCodec }

import com.twitter.util.{ Await, Duration, Future }

package object scalacache extends JavaSerializationCodec {
  private final val logger = LoggerFactory.getLogger(getClass.getName)

  // this alias is just for convenience, so you don't need to import serialization._
  type NoSerialization = scalacache.serialization.InMemoryRepr

  class TypedApi[From, Repr](implicit val scalaCache: ScalaCache[Repr], codec: Codec[From, Repr]) { self =>

    def get(keyParts: Any*)(implicit flags: Flags): Future[Option[From]] = getWithKey(toKey(keyParts))

    def put(keyParts: Any*)(value: From, ttl: Option[Duration] = None)(implicit flags: Flags): Future[Unit] =
      putWithKey(toKey(keyParts), value, ttl)

    def remove(keyParts: Any*): Future[Unit] =
      scalacache.remove(keyParts: _*)

    def removeAll(): Future[Unit] =
      scalacache.removeAll()

    def caching(keyParts: Any*)(f: => Future[From])(implicit flags: Flags): Future[From] = {
      _caching(keyParts: _*)(None)(f)
    }

    def cachingWithTTL(keyParts: Any*)(ttl: Duration)(f: => Future[From])(implicit flags: Flags): Future[From] = {
      _caching(keyParts: _*)(Some(ttl))(f)
    }

    def cachingWithOptionalTTL(keyParts: Any*)(optionalTtl: Option[Duration])(f: => Future[From])(implicit flags: Flags): Future[From] = {
      _caching(keyParts: _*)(optionalTtl)(f)
    }

    private[scalacache] def cachingForMemoize(baseKey: String)(ttl: Option[Duration])(f: => Future[From])(implicit flags: Flags): Future[From] = {
      val key = stringToKey(baseKey)
      _caching(key)(ttl)(f)
    }

    private def _caching(keyParts: Any*)(ttl: Option[Duration])(f: => Future[From])(implicit flags: Flags): Future[From] = {
      val key = toKey(keyParts)
      _caching(key)(ttl)(f)
    }

    private def _caching(key: String)(ttl: Option[Duration])(f: => Future[From])(implicit flags: Flags): Future[From] = {

      def asynchronouslyCacheResult(result: Future[From]): Future[From] = {
        result onSuccess {
          case computedValue =>
            putWithKey(key, computedValue, ttl) rescue {
              case e =>
                if (logger.isWarnEnabled) {
                  logger.warn(s"Failed to write to cache. Key = $key", e)
                }
                Future(())
            }
        }
        result
      }

      def synchronouslyCacheResult(result: Future[From]): Future[From] = {
        for {
          computedValue <- result
          _ <- putWithKey(key, computedValue, ttl) rescue {
            case NonFatal(e) =>
              if (logger.isWarnEnabled) {
                logger.warn(s"Failed to write to cache. Key = $key", e)
              }
              result
          }
        } yield computedValue
      }

      def calculateAndCacheResult(): Future[From] = {
        if (scalaCache.cacheConfig.waitForWriteToComplete)
          synchronouslyCacheResult(f)
        else
          asynchronouslyCacheResult(f)
      }

      val fromCache: Future[Option[From]] = getWithKey(key)

      if (fromCache.isDefined) {
        val o: Try[Option[From]] = Try(Await.result(fromCache))
        o match {
          case Success(Some(value)) => Future.value(value)
          case Success(None) => calculateAndCacheResult()
          case Failure(e) =>
            if (logger.isWarnEnabled) {
              logger.warn(s"Failed to read from cache. Key = $key", e)
            }
            calculateAndCacheResult()
        }
      } else {
        fromCache.rescue[Option[From]] {
          case e =>
            if (logger.isWarnEnabled) {
              logger.warn(s"Failed to read from cache. Key = $key", e)
            }
            Future.value(None)
        }.flatMap {
          case Some(value) => Future.value(value)
          case None => calculateAndCacheResult()
        }
      }

    }

    private def getWithKey(key: String)(implicit flags: Flags): Future[Option[From]] = {
      if (flags.readsEnabled) {
        scalaCache.cache.get[From](key)
      } else {
        if (logger.isDebugEnabled) {
          logger.debug(s"Skipping cache GET because cache reads are disabled. Key: $key")
        }
        Future(None)
      }
    }

    private def putWithKey(key: String, value: From, ttl: Option[Duration] = None)(implicit flags: Flags): Future[Unit] = {
      if (flags.writesEnabled) {
        val finiteTtl = ttl.filter(_.isFinite) 
        scalaCache.cache.put(key, value, finiteTtl)
      } else {
        if (logger.isDebugEnabled) {
          logger.debug(s"Skipping cache PUT because cache writes are disabled. Key: $key")
        }
        Future(())
      }
    }

    /**
     * Synchronous API, for the case when you don't want to deal with Futures.
     */
    object sync {

      def get(keyParts: Any*)(implicit flags: Flags): Option[From] = getSyncWithKey(toKey(keyParts))

      def caching(keyParts: Any*)(f: => From)(implicit flags: Flags): From = {
        _cachingSync(keyParts: _*)(None)(f)
      }

      def cachingWithTTL(keyParts: Any*)(ttl: Duration)(f: => From)(implicit flags: Flags): From = {
        _cachingSync(keyParts: _*)(Some(ttl))(f)
      }

      private[scalacache] def cachingForMemoize(baseKey: String)(ttl: Option[Duration])(f: => From)(implicit flags: Flags): From = {
        val future = self.cachingForMemoize(baseKey)(ttl)(Future(f))(flags)
        Await.result(future, Duration.Top)
      }

      /*
      Note: we could put our clever trousers on and abstract over synchronicity by saying
      `f` has to return an F[From] (where F is either a Future or an Id), but this would leak into the public API
      and probably confuse a lot of users.
       */
      private def _cachingSync(keyParts: Any*)(ttl: Option[Duration])(f: => From)(implicit flags: Flags): From = {
        val future = _caching(keyParts: _*)(ttl)(Future(f))(flags)
        Await.result(future, Duration.Top)
      }

      private def getSyncWithKey(key: String)(implicit flags: Flags): Option[From] =
        Await.result(getWithKey(key), Duration.Top)

    }
  }

  /**
   * Create an instance of the 'typed' API that limits use of the cache to a single type.
   *
   * This means you can't accidentally put something of the wrong type into your cache,
   * and it reduces keystrokes because you don't need to specify types when calling `get`, `put`, etc.
   *
   * e.g. {{{
   * import scalacache._
   * implicit val sc: ScalaCache = ...
   *
   * val intCache = typed[Int]
   *
   * intCache.put("one")(1) // OK
   * intCache.put("two")(2.0) // Nope! Compiler error
   *
   * val one = intCache.get("1") // returns a Future[Option[Int]]
   * }}}
   *
   * @tparam V the type of values that the cache will accept
   */
  def typed[V, Repr](implicit scalaCache: ScalaCache[Repr], codec: Codec[V, Repr]) = new TypedApi[V, Repr]()(scalaCache, codec)

  /**
   * Get the value corresponding to the given key from the cache.
   *
   * Depending on the cache implementation, this may be done synchronously or asynchronously, so it returns a Future.
   *
   * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
   * @tparam V the type of the corresponding value
   * @return the value, if there is one
   */
  def get[V, Repr](keyParts: Any*)(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): Future[Option[V]] =
    typed[V, Repr].get(keyParts: _*)

  /**
   * Convenience method to get a value from the cache synchronously.
   *
   * Warning: may block indefinitely!
   *
   * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
   * @tparam V the type of the corresponding value
   * @return the value, if there is one
   */
  @deprecated("This method has moved. Please use scalacache.sync.get", "0.7.0")
  def getSync[V, Repr](keyParts: Any*)(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): Option[V] =
    sync.get[V, Repr](keyParts: _*)

  /**
   * Insert the given key-value pair into the cache, with an optional Time To Live.
   *
   * Depending on the cache implementation, this may be done synchronously or asynchronously, so it returns a Future.
   *
   * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
   * @param value the value to be cached
   * @param ttl Time To Live (optional, if not specified then the entry will be cached indefinitely)
   * @tparam V the type of the corresponding value
   */
  def put[V, Repr](keyParts: Any*)(value: V, ttl: Option[Duration] = None)(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): Future[Unit] =
    typed[V, Repr].put(keyParts: _*)(value, ttl)

  /**
   * Remove the given key and its associated value from the cache, if it exists.
   * If the key is not in the cache, do nothing.
   *
   * Depending on the cache implementation, this may be done synchronously or asynchronously, so it returns a Future.
   *
   * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
   */
  def remove(keyParts: Any*)(implicit scalaCache: ScalaCache[_]): Future[Unit] =
    scalaCache.cache.remove(toKey(keyParts))

  /**
   * Delete the entire contents of the cache. Use wisely!
   */
  def removeAll()(implicit scalaCache: ScalaCache[_]): Future[Unit] =
    scalaCache.cache.removeAll()

  /**
   * Wrap the given block with a caching decorator.
   * First look in the cache. If the value is found, then return it immediately.
   * Otherwise run the block and save the result in the cache before returning it.
   *
   * All of the above happens asynchronously, so a `Future` is returned immediately.
   * Specifically:
   * - when the cache lookup completes, if it is a miss, the block execution is started.
   * - at some point after the block execution completes, the result is written asynchronously to the cache.
   * - the Future returned from this method does not wait for the cache write before completing.
   *
   * Note: Because no TTL is specified, the result will be stored in the cache indefinitely.
   *
   * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
   * @param f the block to run
   * @tparam V the type of the block's result
   * @return the result, either retrived from the cache or returned by the block
   */
  def caching[V, Repr](keyParts: Any*)(f: => Future[V])(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): Future[V] =
    typed[V, Repr].caching(keyParts: _*)(f)

  /**
   * Wrap the given block with a caching decorator.
   * First look in the cache. If the value is found, then return it immediately.
   * Otherwise run the block and save the result in the cache before returning it.
   *
   * All of the above happens asynchronously, so a `Future` is returned immediately.
   * Specifically:
   * - when the cache lookup completes, if it is a miss, the block execution is started.
   * - at some point after the block execution completes, the result is written asynchronously to the cache.
   * - the Future returned from this method does not wait for the cache write before completing.
   *
   * The result will be stored in the cache until the given TTL expires.
   *
   * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
   * @param ttl Time To Live
   * @param f the block to run
   * @tparam V the type of the block's result
   * @return the result, either retrived from the cache or returned by the block
   */
  def cachingWithTTL[V, Repr](keyParts: Any*)(ttl: Duration)(f: => Future[V])(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): Future[V] =
    typed[V, Repr].cachingWithTTL(keyParts: _*)(ttl)(f)

  def cachingWithOptionalTTL[V, Repr](keyParts: Any*)(optionalTtl: Option[Duration])(f: => Future[V])(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): Future[V] =
    typed[V, Repr].cachingWithOptionalTTL(keyParts: _*)(optionalTtl)(f)

  // Note: this is public because the macro inserts a call to this method into client code
  def cachingForMemoize[V, Repr](key: String)(optionalTtl: Option[Duration])(f: => Future[V])(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): Future[V] =
    typed[V, Repr].cachingForMemoize(key)(optionalTtl)(f)

  private def toKey(parts: Seq[Any])(implicit scalaCache: ScalaCache[_]): String =
    scalaCache.keyBuilder.toCacheKey(parts)(scalaCache.cacheConfig)

  private def stringToKey(string: String)(implicit scalaCache: ScalaCache[_]): String =
    scalaCache.keyBuilder.stringToCacheKey(string)(scalaCache.cacheConfig)

  /**
   * Synchronous API, for the case when you don't want to deal with Futures.
   */
  object sync {

    /**
     * Convenience method to get a value from the cache synchronously.
     *
     * Warning: may block indefinitely!
     *
     * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
     * @tparam V the type of the corresponding value
     * @return the value, if there is one
     */
    def get[V, Repr](keyParts: Any*)(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): Option[V] =
      typed[V, Repr].sync.get(keyParts: _*)

    /**
     * Wrap the given block with a caching decorator.
     * First look in the cache. If the value is found, then return it immediately.
     * Otherwise run the block and save the result in the cache before returning it.
     *
     * Note: Because no TTL is specified, the result will be stored in the cache indefinitely.
     *
     * Warning: may block indefinitely!
     *
     * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
     * @param f the block to run
     * @tparam V the type of the block's result
     * @return the result, either retrived from the cache or returned by the block
     */
    def caching[V, Repr](keyParts: Any*)(f: => V)(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): V =
      typed[V, Repr].sync.caching(keyParts: _*)(f)

    /**
     * Wrap the given block with a caching decorator.
     * First look in the cache. If the value is found, then return it immediately.
     * Otherwise run the block and save the result in the cache before returning it.
     *
     * The result will be stored in the cache until the given TTL expires.
     *
     * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
     * @param ttl Time To Live
     * @param f the block to run
     * @tparam V the type of the block's result
     * @return the result, either retrived from the cache or returned by the block
     */
    def cachingWithTTL[V, Repr](keyParts: Any*)(ttl: Duration)(f: => V)(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): V =
      typed[V, Repr].sync.cachingWithTTL(keyParts: _*)(ttl)(f)

    /**
     * Wrap the given block with a caching decorator.
     * First look in the cache. If the value is found, then return it immediately.
     * Otherwise run the block and save the result in the cache before returning it.
     *
     * The result will be stored in the cache until the given TTL expires.
     *
     * @param keyParts data to be used to generate the cache key. This could be as simple as just a single String. See [[CacheKeyBuilder]].
     * @param optionalTtl Optional Time To Live
     * @param f the block to run
     * @tparam V the type of the block's result
     * @return the result, either retrived from the cache or returned by the block
     */
    def cachingWithOptionalTTL[V, Repr](keyParts: Any*)(optionalTtl: Option[Duration])(f: => V)(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): V = {
      optionalTtl match {
        case Some(ttl) => typed[V, Repr].sync.cachingWithTTL(keyParts: _*)(ttl)(f)
        case None => typed[V, Repr].sync.caching(keyParts: _*)(f)
      }
    }

    // Note: this is public because the macro inserts a call to this method into client code
    def cachingForMemoize[V, Repr](key: String)(optionalTtl: Option[Duration])(f: => V)(implicit scalaCache: ScalaCache[Repr], flags: Flags, codec: Codec[V, Repr]): V =
      typed[V, Repr].sync.cachingForMemoize(key)(optionalTtl)(f)

  }

}
