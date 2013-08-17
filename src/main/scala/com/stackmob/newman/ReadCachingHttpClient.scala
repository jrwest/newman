/**
 * Copyright 2012-2013 StackMob
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stackmob.newman

import scalaz.Scalaz._
import scalaz.effect.IO
import caching._
import request._
import response.HttpResponse
import java.net.URL
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class ReadCachingHttpClient(httpClient: HttpClient,
                            httpResponseCacher: HttpResponseCacher,
                            t: Milliseconds,
                            defaultDuration: Duration = 500.milliseconds)
                           (implicit ctx: ExecutionContext) extends HttpClient {
  import ReadCachingHttpClient._

  override def get(u: URL, h: Headers): GetRequest = new GetRequest with CachingMixin {
    override protected lazy val ttl = t
    override protected val cache = httpResponseCacher
    override protected def doHttpRequest(h: Headers) = {
      httpClient.get(u, h).prepare(defaultDuration)
    }
    override val url = u
    override val headers = h
  }

  override def post(u: URL, h: Headers, b: RawBody): PostRequest = new PostRequest {
    override def prepareAsync: IO[Future[HttpResponse]] = httpClient.post(u, h, b).prepareAsync
    override val url = u
    override val headers = h
    override val body = b
  }

  override def put(u: URL, h: Headers, b: RawBody): PutRequest = new PutRequest {
    override def prepareAsync: IO[Future[HttpResponse]] = {
      httpClient.put(u, h, b).prepareAsync
    }
    override val url = u
    override val headers = h
    override val body = b
  }

  override def delete(u: URL, h: Headers): DeleteRequest = new DeleteRequest {
    override def prepareAsync: IO[Future[HttpResponse]] = {
      httpClient.delete(u, h).prepareAsync
    }
    override val url = u
    override val headers = h
  }

  override def head(u: URL, h: Headers): HeadRequest = new HeadRequest with CachingMixin {
    override protected lazy val ttl = t
    override protected val cache = httpResponseCacher
    override protected def doHttpRequest(h: Headers) = {
      httpClient.head(u, h).prepare(defaultDuration)
    }
    override val url = u
    override val headers = h
  }
}

object ReadCachingHttpClient {
  trait CachingMixin { this: HttpRequest =>
    protected def ttl: Milliseconds
    protected def cache: HttpResponseCacher
    protected def doHttpRequest(headers: Headers): IO[HttpResponse]

    override def prepareAsync: IO[Future[HttpResponse]] = {
      cache.get(this).flatMap { mbCachedResponse: Option[HttpResponse] =>
        mbCachedResponse some { resp =>
          Future.successful(resp).pure[IO]
        } none {
          doHttpRequest(headers).flatMap { response: HttpResponse =>
            cache.set(this, response, ttl).map { _ =>
              Future.successful(response)
            }
          }
        }
      }
    }
  }
}
