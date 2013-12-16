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
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.model.android.DrawingCache;
import master.flame.danmaku.danmaku.model.android.DrawingCachePoolManager;
import master.flame.danmaku.danmaku.model.objectpool.Pool;
import master.flame.danmaku.danmaku.model.objectpool.Pools;
import master.flame.danmaku.danmaku.parser.DanmakuFactory;
import master.flame.danmaku.danmaku.util.DanmakuUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class CacheManagingDrawTask extends DrawTask {

    private CacheManager mCacheManager;

    private DanmakuTimer mCacheTimer;

    public CacheManagingDrawTask(DanmakuTimer timer, Context context, int dispW, int dispH,
            TaskListener taskListener, int maxCacheSize) {
        super(timer, context, dispW, dispH, taskListener);
        mCacheManager = new CacheManager(maxCacheSize, 2);
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
        mCacheTimer.update(mills);
        reset();
    }

    @Override
    public void quit() {
        mCacheManager.end();
        super.quit();
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

        Pool<DrawingCache> mCachePool = Pools.finitePool(mCachePoolManager, 300);

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

        public void resume() {
            mHandler.sendEmptyMessage(CacheHandler.RESUME);
        }

        public void pause() {
            mHandler.sendEmptyMessage(CacheHandler.PAUSE);
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
            Iterator<BaseDanmaku> it = mCaches.iterator();
            while (it.hasNext()) {
                BaseDanmaku val = it.next();
                if (val.isTimeOut()) {
                    entryRemoved(false, val, null);
                    it.remove();
                } else {
                    break;
                }
            }
        }

        public class CacheHandler extends Handler {

            private static final int PREPARE = 4;

            public static final int ADD_DANMAKKU = 5;

            public static final int BUILD_CACHES = 1;

            public static final int PAUSE = 2;

            public static final int RESUME = 3;

            private boolean mPause;

            public CacheHandler(android.os.Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                int what = msg.what;
                switch (what) {
                    case PREPARE:
                        for (int i = 0; i < 200; i++) {
                            mCachePool.release(new DrawingCache());
                        }
                    case BUILD_CACHES:
                        if (!mPause) {
                            long waitTime = mCacheTimer.currMillisecond - mTimer.currMillisecond;
                            if (waitTime > 1000
                                    && waitTime <= DanmakuFactory.MAX_DANMAKU_DURATION
                                            * mScreenSize) {
                                sendEmptyMessageDelayed(BUILD_CACHES, waitTime - 1000);
                                return;
                            }
                            mCacheTimer.update(mTimer.currMillisecond);
                            clearTimeOutCaches();
                            prepareCaches(mTaskListener == null);
                            sendEmptyMessage(BUILD_CACHES);
                            if (mTaskListener != null) {
                                mTaskListener.ready();
                                mTaskListener = null;
                            }
                        }
                        break;
                    case PAUSE:
                        mPause = true;
                        break;
                    case RESUME:
                        mPause = false;
                        sendEmptyMessage(BUILD_CACHES);
                        break;
                    case ADD_DANMAKKU:
                        synchronized (danmakuList) {
                            CacheManagingDrawTask.super.addDanmaku((BaseDanmaku) msg.obj);
                        }
                        break;
                }
            }

            private long prepareCaches(boolean useTimeCounter) {

                long curr = mCacheTimer.currMillisecond;
                long startTime = System.currentTimeMillis();
                Danmakus danmakus = null;
                synchronized (danmakuList) {
                    danmakus = (Danmakus) danmakuList.sub(curr, curr
                            + DanmakuFactory.MAX_DANMAKU_DURATION * mScreenSize);
                }

                if (danmakus == null || danmakus.size() == 0)
                    return 0;
                Iterator<BaseDanmaku> itr = danmakus.iterator();

                BaseDanmaku item = null;
                long consumingTime = 0;
                while (itr.hasNext()) {
                    item = itr.next();

                    if (item.isTimeOut() || !item.isOutside()) {
                        continue;
                    }

                    // measure
                    if (!item.isMeasured()) {
                        synchronized (danmakuList) {
                            item.measure(mDisp);
                        }
                    }

                    // build cache
                    if (!item.hasDrawingCache()) {
                        try {
                            synchronized (danmakuList) {
                                DrawingCache cache = mCachePool.acquire();

                                DrawingCache newCache = DanmakuUtils.buildDanmakuDrawingCache(item,
                                        mDisp, cache);
                                item.cache = newCache;
                                boolean quitLoop = false;
                                if (mRealSize + newCache.size() > mMaxSize) {
                                    quitLoop = true;
                                }
                                boolean pushed = mCacheManager.push(item);
                                if (!pushed) {
                                    mCachePool.release(newCache);
                                    newCache.destroy();
                                    break;
                                }
                                if (quitLoop)
                                    break;
                            }

                        } catch (OutOfMemoryError e) {
                            break;
                        } catch (Exception e) {
                            break;
                        }
                    }

                    if (useTimeCounter) {
                        consumingTime = System.currentTimeMillis() - startTime;
                        if (consumingTime >= DanmakuFactory.MAX_DANMAKU_DURATION) {
                            break;
                        }
                    }

                }

                consumingTime = System.currentTimeMillis() - startTime;
                mCacheTimer.add(consumingTime);

                return consumingTime;
            }

            public void begin() {
                // sendEmptyMessage(BUILD_CACHES);
                sendEmptyMessage(PREPARE);
            }

            public void pause() {
                sendEmptyMessage(PAUSE);
            }

            public void resume() {
                sendEmptyMessage(RESUME);
            }
        }

    }
}
