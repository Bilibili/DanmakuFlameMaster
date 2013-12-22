/*
 * Copyright (C) 2013 Chen Hui <calmer91@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package master.flame.danmaku.controller;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.model.android.DrawingCache;
import master.flame.danmaku.danmaku.model.android.DrawingCachePoolManager;
import master.flame.danmaku.danmaku.model.objectpool.Pool;
import master.flame.danmaku.danmaku.model.objectpool.Pools;
import master.flame.danmaku.danmaku.parser.DanmakuFactory;
import master.flame.danmaku.danmaku.util.DanmakuUtils;

public class CacheManagingDrawTask extends DrawTask {

    private static final int MAX_CACHE_SCREEN_SIZE = 3;

    private int mMaxCacheSize = 2;

    private CacheManager mCacheManager;

    private DanmakuTimer mCacheTimer;

    private Object mDrawingNotify = new Object();

    public CacheManagingDrawTask(DanmakuTimer timer, Context context, int dispW, int dispH,
                                 TaskListener taskListener, int maxCacheSize) {
        super(timer, context, dispW, dispH, taskListener);
        mMaxCacheSize = maxCacheSize;
        mCacheManager = new CacheManager(maxCacheSize, MAX_CACHE_SCREEN_SIZE);
    }

    @Override
    protected void initTimer(DanmakuTimer timer) {
        mTimer = timer;
        mCacheTimer = new DanmakuTimer();
        mCacheTimer.update(timer.currMillisecond);
    }

    @Override
    public void addDanmaku(BaseDanmaku danmaku) {
        if (mCacheManager == null)
            return;
        mCacheManager.addDanmaku(danmaku);
    }

    @Override
    public void draw(Canvas canvas) {
        synchronized (danmakuList) {
            super.draw(canvas);
        }
        synchronized(mDrawingNotify){
            mDrawingNotify.notify();
        }
    }

    @Override
    public void reset() {
        // mCacheTimer.update(mTimer.currMillisecond);
        if (mRenderer != null)
            mRenderer.clear();
        mCacheManager.evictAll();
    }

    @Override
    public void seek(long mills) {
        mTimer.update(mills);
        if (mRenderer != null)
            mRenderer.clear();
        mCacheManager.evictAllNotInScreen();
        mCacheManager.resume();
    }

    @Override
    public void start() {
        if (mCacheManager == null) {
            mCacheManager = new CacheManager(mMaxCacheSize, MAX_CACHE_SCREEN_SIZE);
            mCacheManager.begin();
        } else {
            mCacheManager.resume();
        }
    }

    @Override
    public void quit() {
        mCacheManager.end();
        super.quit();
        reset();
    }

    @Override
    public void prepare() {
        assert (mParser != null);
        loadDanmakus(mParser);
        mCacheManager.begin();
    }

    public class CacheManager {

        private static final String TAG = "CacheManager";

        public HandlerThread mThread;

        List<BaseDanmaku> mCaches = new ArrayList<BaseDanmaku>();

        DrawingCachePoolManager mCachePoolManager = new DrawingCachePoolManager();

        Pool<DrawingCache> mCachePool = Pools.finitePool(mCachePoolManager, 500);

        private int mMaxSize;

        private int mRealSize;

        private int mScreenSize = 2;

        private CacheHandler mHandler;

        public CacheManager(int maxSize, int screenSize) {
            mRealSize = 0;
            mMaxSize = maxSize;
            mScreenSize = screenSize;
        }

        public void addDanmaku(BaseDanmaku danmaku) {
            if (mHandler != null) {
                mHandler.obtainMessage(CacheHandler.ADD_DANMAKKU, danmaku).sendToTarget();
            }
        }

        public void begin() {
            if (mThread == null) {
                mThread = new HandlerThread("Cache Building Thread");
                mThread.start();
            }
            if (mHandler == null)
                mHandler = new CacheHandler(mThread.getLooper());
            mHandler.begin();
        }

        public void end() {
            if (mHandler != null) {
                mHandler.pause();
                mHandler.removeCallbacksAndMessages(null);
                mHandler = null;
            }
            if (mThread != null) {
                mThread.quit();
                try {
                    mThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                mThread = null;
            }
            evictAll();
            clearCachePool();
        }

        public void resume() {
            if (mHandler != null) {
                mHandler.resume();
            } else {
                begin();
            }
        }

        private synchronized void evictAll() {
            if (mCaches != null) {
                for (BaseDanmaku danmaku : mCaches) {
                    entryRemoved(true, danmaku, null);
                }
                mCaches.clear();
            }
            mRealSize = 0;
        }

        private synchronized void evictAllNotInScreen() {
            if (mCaches != null) {
                for (BaseDanmaku danmaku : mCaches) {
                    if(danmaku.isOutside()){
                        entryRemoved(true, danmaku, null);
                    }
                }
                mCaches.clear();
            }
            mRealSize = 0;
        }

        protected void entryRemoved(boolean evicted, BaseDanmaku oldValue, BaseDanmaku newValue) {
            mRealSize -= sizeOf(oldValue);
            if (oldValue.cache != null) {
                mCachePool.release((DrawingCache) oldValue.cache);
                oldValue.cache.destroy();
                oldValue.cache = null;
            }
        }

        protected int sizeOf(BaseDanmaku value) {
            if (value.cache != null) {
                return value.cache.size();
            }
            return 0;
        }

        private void clearCachePool() {
            DrawingCache item;
            while ((item = mCachePool.acquire()) != null) {
                item.destroy();
            }
        }

        private synchronized boolean push(BaseDanmaku item) {
            int size = sizeOf(item);
            while (mRealSize + size > mMaxSize && mCaches.size() > 0) {
                BaseDanmaku oldValue = mCaches.get(0);
                if (oldValue.isTimeOut()) {
                    entryRemoved(false, oldValue, item);
                    mCaches.remove(oldValue);
                } else {
                    return false;
                }
            }
            this.mCaches.add(item);
            mRealSize += size;
            return true;
        }

        private synchronized void clearTimeOutCaches() {
            clearTimeOutCaches(mTimer.currMillisecond);
        }

        private synchronized void clearTimeOutCaches(long time) {
            Iterator<BaseDanmaku> it = mCaches.iterator();
            while (it.hasNext()) {
                BaseDanmaku val = it.next();
                if (val.isTimeOut(time)) {
                    entryRemoved(false, val, null);
                    it.remove();
                }
            }
        }

        public class CacheHandler extends Handler {

            private static final int PREPARE = 4;

            public static final int ADD_DANMAKKU = 5;

            public static final int BUILD_CACHES = 1;

            private boolean mPause;

            public CacheHandler(android.os.Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                int what = msg.what;
                switch (what) {
                    case PREPARE:
                        evictAllNotInScreen();
                        for (int i = 0; i < 200; i++) {
                            mCachePool.release(new DrawingCache());
                        }
                    case BUILD_CACHES:
                        if (!mPause) {
                            clearTimeOutCaches();
                            long waitTime = mCacheTimer.currMillisecond - mTimer.currMillisecond;
                            long maxCacheDuration = DanmakuFactory.MAX_DANMAKU_DURATION
                                    * mScreenSize;
//                            Log.e("cache remain", waitTime+"ms");
                            if (waitTime > 1000 && waitTime <= maxCacheDuration) {
                                sendEmptyMessageDelayed(BUILD_CACHES, waitTime - 1000);
                                return;
                            } else if (waitTime < 0){
                                evictAll();
                            } else if (getFirstCacheTime() - mTimer.currMillisecond > maxCacheDuration){
                                evictAllNotInScreen();
                            }
                            if(waitTime < 0 || waitTime > maxCacheDuration){
                                mCacheTimer.update(mTimer.currMillisecond + 100);
                            }
                            prepareCaches(mTaskListener != null);
                            sendEmptyMessage(BUILD_CACHES);
                            if (mTaskListener != null) {
                                mTaskListener.ready();
                                mTaskListener = null;
                            }
                        }
                        break;
                    case ADD_DANMAKKU:
                        synchronized (danmakuList) {
                            CacheManagingDrawTask.super.addDanmaku((BaseDanmaku) msg.obj);
                        }
                        break;
                }
            }

            private long prepareCaches(boolean init) {

                long curr = mCacheTimer.currMillisecond;
                long startTime = System.currentTimeMillis();
                Set<BaseDanmaku> danmakus = null;
                danmakus = danmakuList.subset(curr, curr
                        + DanmakuFactory.MAX_DANMAKU_DURATION * mScreenSize);

                if (danmakus == null || danmakus.size() == 0)
                    return 0;
                Iterator<BaseDanmaku> itr = danmakus.iterator();

                BaseDanmaku item = null;
                long consumingTime = 0;
                int count = 0;
                while (itr.hasNext() && !mPause) {
                    item = itr.next();
                    count++;
                    if (item.isTimeOut() || !item.isOutside()) {
                        continue;
                    }

                    // measure
                    if (!item.isMeasured()) {
                        item.measure(mDisp);
                    }

                    // build cache
                    if (!item.hasDrawingCache()) {

                        if (!init) {
                            try {
                                synchronized (mDrawingNotify) {
                                    mDrawingNotify.wait(100);
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        try {

                            // guess cache size
                            int cacheSize = DanmakuUtils.getCacheSize((int) item.paintWidth,
                                    (int) item.paintHeight);
                            if (mRealSize + cacheSize > mMaxSize) {
//                                    Log.d("cache", "break at MaxSize:"+mMaxSize);
                                break;
                            }

                            DrawingCache cache = mCachePool.acquire();
                            synchronized (danmakuList) {
                                DrawingCache newCache = DanmakuUtils.buildDanmakuDrawingCache(item,
                                        mDisp, cache);
                                item.cache = newCache;
                                boolean pushed = mCacheManager.push(item);
                                if (!pushed) {
                                    item.cache = null;
                                    mCachePool.release(newCache);
                                    newCache.destroy();
//                                  Log.d("cache", "break at push failed:"+mMaxSize);
                                    break;
                                }
                            }

                        } catch (OutOfMemoryError e) {
//                            Log.d("cache", "break at OutOfMemoryError");
                            break;
                        } catch (Exception e) {
//                            Log.d("cache", "break at :"+e.getMessage());
                            break;
                        }


                    }



                    if (!init) {
                        consumingTime = System.currentTimeMillis() - startTime;

                        if (consumingTime >= DanmakuFactory.COMMON_DANMAKU_DURATION) {
//                            Log.d("cache", "break at consumingTime out:"+consumingTime);
                            break;
                        }
                    }

                }

                consumingTime = System.currentTimeMillis() - startTime;
                if (item != null){
                    mCacheTimer.update(item.time);
//                    Log.i("cache", "stop at :"+item.time+","+count+",size:"+danmakus.size());
                }
                return consumingTime;
            }

            public void begin() {
                sendEmptyMessage(PREPARE);
            }

            public void pause() {
                mPause = true;
            }

            public void resume() {
                mPause = false;
                sendEmptyMessage(BUILD_CACHES);
            }

            public boolean isPause() {
                return mPause;
            }
        }

        public long getFirstCacheTime() {
            if(mCaches!=null && mCaches.size()>0){
                return mCaches.get(0).time;
            }
            return 0;
        }

    }
}