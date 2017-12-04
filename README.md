

Cache mCache;
Network mNetwork;
ResponseDelivery mDelivery;
NetworkDispatcher[] mDispatchers;

1.Volley

提供了两个重载的newRequestQueue方法，用以返回一个RequestQueue

```
public static RequestQueue newRequestQueue(Context context, HttpStack stack) {
        File cacheDir = new File(context.getCacheDir(), DEFAULT_CACHE_DIR);;

        if (stack == null) {
            if (Build.VERSION.SDK_INT >= 9) {
                stack = new HurlStack();
            } else {
                stack = new HttpClientStack(AndroidHttpClient.newInstance(userAgent));
            }
        }

        Network network = new BasicNetwork(stack);

        RequestQueue queue = new RequestQueue(new DiskBasedCache(cacheDir), network);
        queue.start();

        return queue;
    }
```

在RequestQueue的构造函数中我们传入了 DiskBasedCache 和 BasicNetwork 对象
紧接着调用了start方法

```
public void start() {
       mCacheDispatcher = new CacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
       mCacheDispatcher.start();

       for (int i = 0; i < mDispatchers.length; i++) {
           NetworkDispatcher networkDispatcher = new NetworkDispatcher(mNetworkQueue, mNetwork,
                   mCache, mDelivery);
           mDispatchers[i] = networkDispatcher;
           networkDispatcher.start();
       }
   }

```
在start方法中调用了CacheDispatcher的start方法，并循环调用了NetworkDispatcher的start方法，mDispatchers数组的
默认大小为4，我们也可以在三个参数的构造方法的中指定它的大小。

CacheDispatcher 继承自Thread，所以其是一个单独的线程。当调用它的start方法时，CacheDispatcher 类中的run方法会执行

```
public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        mCache.initialize();

        while (true) {
            try {
                // Get a request from the cache triage queue, blocking until
                // at least one is available.
                final Request<?> request = mCacheQueue.take();

                // If the request has been canceled, don't bother dispatching it.
                if (request.isCanceled()) {
                    continue;
                }
                // Attempt to retrieve this item from cache.
                // 尝试从缓存里拿这个请求
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {
                    // Cache miss; send off to the network dispatcher.
                    // 如果没拿到，交给网络处理。
                    mNetworkQueue.put(request);
                    continue;
                }
                // If it is completely expired, just send it to the network.
                // 如果已经过期了，交给网络处理。
                if (entry.isExpired()) {
                    request.setCacheEntry(entry);
                    mNetworkQueue.put(request);
                    continue;
                }
                // We have a cache hit; parse its data for delivery back to the request.
                // 如果拿到了，且没有过期。
                Response<?> response = request.parseNetworkResponse(new NetworkResponse(entry.data, entry.responseHeaders));
                if (!entry.refreshNeeded()) {
                    // Completely unexpired cache hit. Just deliver the response.
                    mDelivery.postResponse(request, response);
                } else {
                    // Soft-expired cache hit. We can deliver the cached response,
                    // but we need to also send the request to the network for
                    // refreshing.
                    request.setCacheEntry(entry);
                    // Mark the response as intermediate.
                    response.intermediate = true;
                    // Post the intermediate response back to the user and have
                    // the delivery then forward the request along to the network.
                    mDelivery.postResponse(request, response, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mNetworkQueue.put(request);
                            } catch (InterruptedException e) {
                                // Not much we can do about this.
                            }
                        }
                    });
                }
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }
        }
    }
```

run方法是一个死循环，它不断地从mCacheQueue里取数据进行处理。mCacheQueue 是一个BlockingQueue，这个Queue实现了生产者-消费者队列模型，
当队列中没有数据也就是Request对象的的时候这个线程就会阻塞。
从队列中取出一个request之后，先去缓存里看看有没有，如果没有，就交给网络处理。
如果有，但是过期了，也交给网络处理。
如果有，且是新鲜的，就直接把这个response发出去。
如果有，但是不新鲜了，把response发出去的同时，用网络刷新一下一整个请求。

发出去的这个动作调用的是mDelivery的postResponse这个方法。mDelivery是ExecutorDelivery类型的。它在RequestQueue的构造函数中被初始化。
其中的Handler被关联到了主线程。

```
public RequestQueue(Cache cache, Network network, int threadPoolSize) {
    this(cache, network, threadPoolSize,
            new ExecutorDelivery(new Handler(Looper.getMainLooper())));
}
```
ExecutorDelivery中使用一个Executor包装了RequestQueue传进来的handler。
内部类ResponseDeliveryRunnable实现了Runnable接口，它的实例会通过postResponse方法被Handler发送到主线程执行。

```
        @Override
        public void run() {
            // Deliver a normal response or error, depending.
            if (mResponse.isSuccess()) {
                mRequest.deliverResponse(mResponse.result);
            } else {
                mRequest.deliverError(mResponse.error);
            }
       }
```

我们在项目的MainActivity里写Volley的网络请求的时候，new了一个StringRequest对象。在new对象的时候，传入了
Response里的Listener接口，并重写了其中的onResponse方法。
```
StringRequest stringRequest = new StringRequest(Request.Method.GET, url, new Response.Listener<String>()
                                                       {
                                                           @Override
                                                           public void onResponse(String response)
                                                           {
                                                               Log.i(TAG, response);
                                                           }
                                                       }, new Response.ErrorListener()
       {
           @Override
           public void onErrorResponse(VolleyError error)
           {

           }
       });
```

deliverResponse的方法实现如下。
```
@Override
  protected void deliverResponse(String response) {
      mListener.onResponse(response);
  }
```
这样一个有缓存的请求就完成了。


如果缓存中没有这个请求，那么就会交给网络处理。
如上边代码所示，交给网络处理，其实是把这个请求放到了mNetworkQueue里。
那么看一下NetworkDispatcher

```
@Override
   public void run() {
       Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
       while (true) {
           long startTimeMs = SystemClock.elapsedRealtime();
           Request<?> request;
               request = mQueue.take();

               if (request.isCanceled()) {
                   continue;
               }

               addTrafficStatsTag(request);
               // Perform the network request.
               NetworkResponse networkResponse = mNetwork.performRequest(request);
               request.addMarker("network-http-complete");

               // If the server returned 304 AND we delivered a response already,
               // we're done -- don't deliver a second identical response.
               if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                   request.finish("not-modified");
                   continue;
               }

               // Parse the response here on the worker thread.
               Response<?> response = request.parseNetworkResponse(networkResponse);
               request.addMarker("network-parse-complete");

               // Write to cache if applicable.
               // TODO: Only update cache metadata instead of entire record for 304s.
               if (request.shouldCache() && response.cacheEntry != null) {
                   mCache.put(request.getCacheKey(), response.cacheEntry);
                   request.addMarker("network-cache-written");
               }

               // Post the response back.
               request.markDelivered();
               mDelivery.postResponse(request, response);
           } catch (VolleyError volleyError) {
               volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
               parseAndDeliverNetworkError(request, volleyError);
           } catch (Exception e) {
               VolleyLog.e(e, "Unhandled exception %s", e.toString());
               VolleyError volleyError = new VolleyError(e);
               volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
               mDelivery.postError(request, volleyError);
           }
       }
   }

```
