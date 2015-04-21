package tv.danmaku.ijk.media.demo;

import android.text.TextUtils;
import android.util.Log;
import org.apache.http.HttpRequest;
import org.apache.http.ProtocolVersion;
import org.apache.http.message.BasicHttpRequest;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreamProxy implements Runnable {

    private static final String LOG_TAG = StreamProxy.class.getSimpleName();

    private int port = 0;

    private String url;
    private ExecutorService pool = null;
    private ArrayList<RandomAccessFile> mRandomAccessFilesList = new ArrayList<RandomAccessFile>();
    private long mFileSize = 0;

    private HashMap<Long, DownloadBlock> downloadBlocks = new HashMap<Long, DownloadBlock>();
    private ArrayList<Long> blockStartPositions = new ArrayList<Long>();

    private File tempCacheDir;
    private File saveCacheDir;

    private ProxyCallback proxyCallback;
    private boolean mIsComplete = false;
    // 当前正在进行下载的现成
    private DownloadTask downloadThread;

    public interface ProxyCallback {
        public void onError();

        public void onNetError();
    }

    public StreamProxy(String url, ProxyCallback proxyCallback) {
        super();
        this.url = url;
        this.proxyCallback = proxyCallback;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return isRunning;
    }

    private boolean isRunning = false;
    private ServerSocket mServerSocket;
    private ArrayList<Socket> mSockets = new ArrayList<Socket>();
    private Thread thread;

    public void init() {
        try {
            mIsComplete = false;
            mServerSocket = new ServerSocket(port, 0, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            mServerSocket.setSoTimeout(500);
            port = mServerSocket.getLocalPort();
            pool = Executors.newSingleThreadExecutor();
            Log.d(LOG_TAG, "port " + port + " obtained");
        } catch (UnknownHostException e) {
            Log.e(LOG_TAG, "Error initializing server", e);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error initializing server", e);
        }
    }

    public void start() {
        if (mServerSocket == null) {
            return;
            // throw new IllegalStateException("Cannot start proxy; it has not been initialized.");
        }

        isRunning = true;
        thread = new Thread(this, "thread-StreamProxy");
        thread.start();
    }

    public void stop(boolean isPlayComplete) {
        Log.d(LOG_TAG, "stopping--" + Thread.currentThread().getName());

        // 关闭所有连接
        synchronized (mSockets) {
            for (Socket s : mSockets) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            mSockets.clear();
        }

        isRunning = false;
        if (thread == null) {
            return;
        }

        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        thread = null;

//        stopDownload(isPlayComplete);

        if (null != mServerSocket) {
            try {
                mServerSocket.close();
            } catch (Exception e) {
                Log.w(LOG_TAG,e);
            }
        }

        downloadThread.stop();
        downloadThread.release();
    }

    public void run() {
        Log.d(LOG_TAG, "running");
        while (isRunning) {
            try {
//                Log.d(LOG_TAG, "waiting accept");
                final Socket client = mServerSocket.accept();
                if (client == null) {
                    continue;
                }

                synchronized (mSockets) {
                    mSockets.clear();
                    mSockets.add(client);
                }
                // 获取请求
                final BlockHttpRequest request = readRequest(client);

                Log.d(LOG_TAG, "Javan new Client is:[" + client.getPort() + "]" + " request start " + request.rangeStart);

                if (isRunning) {
                    if (downloadThread == null) {
                        downloadThread = new DownloadTask(request);
                        downloadThread.start();
                    } else {
                        // 已经有现在现成了
                        downloadThread.seekTo(request);
                    }
                }
            } catch (SocketTimeoutException e) {
//                Log.w(LOG_TAG, "SocketTimeoutException");
            } catch (IOException e) {
//                Log.w(LOG_TAG, "Error connecting to client", e);
            }
        }
        Log.d(LOG_TAG, "Proxy interrupted. Shutting down.");
    }

    // 当前Http请求的块
    public static class BlockHttpRequest {
        String url;
        String method;
        String user_agent;
        long rangeStart = -1;
        long rangeEnd   = -1;
        long downloadStart = -1;// 下载数据的实际起始位置
        // 发起块请求的 client
        Socket client;

        /**
         * 根据块数据生成网络请求。
         * @return 返回网络请求。
         */
        HttpRequest obtain() {
            HttpRequest request = new BasicHttpRequest(method, url, new ProtocolVersion("HTTP", 1, 1));

            if (downloadStart >= 0) {
                StringBuilder range = new StringBuilder(20);
                range.append("bytes=").append(downloadStart).append('-');
                if (rangeEnd > 0) {
                    range.append(rangeEnd);
                }
                request.addHeader("Range", range.toString());
                Log.d(LOG_TAG, "Request range:" + range.toString());
            }
            if (user_agent != null)
                request.addHeader("User-Agent", user_agent);

            return request;
        }

        public BlockHttpRequest copy() {
            BlockHttpRequest res = new BlockHttpRequest();

            res.method = this.method;
            res.user_agent = user_agent;
            res.url = url;

            res.client = client;
            res.rangeStart = rangeStart;
            res.rangeEnd = rangeEnd;
            res.downloadStart = downloadStart;

            return res;
        }
    }

    private BlockHttpRequest readRequest(Socket client) {
        BlockHttpRequest request = null;
        InputStream is;
        String firstLine;
        String range = null;
        String ua = null;
        try {
            is = client.getInputStream();
            // BufferedReader reader = new BufferedReader(new InputStreamReader(is),
            // 8192);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is), 1024);
            firstLine = reader.readLine();
//            System.out.println("ijk请求 : " + firstLine);
            String line = null;
            boolean rangeRead = false;
            boolean uaRead = false;

            do {
                line = reader.readLine();
//                System.out.println("ijk请求 : " + line);
                if (line != null && line.toLowerCase().startsWith("range: ")) {
                    range = line.substring(13);
                    rangeRead = true;
                }
                if (line != null && line.toLowerCase().startsWith("user-agent: ")) {
                    ua = line.substring(12);
                    uaRead = true;
                }

                if (rangeRead && uaRead) {
                    break;
                }

            } while (!TextUtils.isEmpty(line) && reader.ready());
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error parsing request", e);
            return request;
        }

        if (firstLine == null) {
            Log.i(LOG_TAG, "Proxy client closed connection without a request.");
            return request;
        }

        StringTokenizer st = new StringTokenizer(firstLine);
        String method = st.nextToken();
        String uri = st.nextToken();
//        Log.d(LOG_TAG, uri);
        String realUri = uri.substring(1);
//        Log.d(LOG_TAG, realUri);
        request = new BlockHttpRequest();

        request.method = method;
        request.url = realUri;
        request.user_agent = ua;
        request.client = client;

        int index = range.indexOf('-');
        if (index > 0) {
            try {
                String startStr = range.substring(0, index);
                request.rangeStart = Long.parseLong(startStr);
                // 默认下载位置等于起始位置
                request.downloadStart = request.rangeStart;
                if (index < (range.length()-2)) {
                    String endStr = range.substring(index+1);
                    request.rangeEnd = Long.parseLong(endStr);
                }

            } catch (NumberFormatException nfe) {
                Log.w(LOG_TAG, nfe);
            }
        }

        return request;
    }

    private static long total_file = 0;
    // 存储已经保存好的块数据
    private static HashMap<Long, Long> downloadedBlock = new HashMap<Long, Long>(30);

//    private class DownloadThread extends Thread {
//        private final BlockHttpRequest mBlockRequest;
//        private final Socket mClient;
//
//        RandomAccessFile randomAccessFile = null;
//        private InputStream data;
//        private MyClientConnManager mgr;
//        // 已经写入的数据总量
//        private long write_total = 0;
//        // 下载的起始位置
//        private long startFrom;
//        private boolean just_write_file_data = false;
//
//        public DownloadThread(BlockHttpRequest request) {
//            mBlockRequest = request;
//            mClient = mBlockRequest.client;
//        }
//
//        public String startThread() throws IOException {
//            Log.e(LOG_TAG, "processing ---");
//            String url = mRequest.getRequestLine().getUri();
//            HttpParams httpParams = new BasicHttpParams();
//            DefaultHttpClient seed = new DefaultHttpClient(httpParams);
//            SchemeRegistry registry = new SchemeRegistry();
//            registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
//            mgr = new MyClientConnManager(seed.getParams(), registry);
//            DefaultHttpClient http = new DefaultHttpClient(mgr, seed.getParams());
//            HttpGet method = new HttpGet(url);
//
//            for (Header h : mRequest.getAllHeaders()) {
//                method.addHeader(h);
//            }
//
//            HttpResponse realResponse = null;
//            try {
//                Log.d(LOG_TAG, "starting download");
//                if (downloadedBlock.size() == 0)
//                    realResponse = http.execute(method);
//                Log.d(LOG_TAG, "downloaded");
//            } catch (ClientProtocolException e) {
//                Log.e(LOG_TAG, "Error downloading", e);
//                if (null != proxyCallback) {
//                    proxyCallback.onError();
//                }
//            } catch (IOException e) {
//                Log.e(LOG_TAG, "Error downloading", e);
//                if (null != proxyCallback) {
//                    proxyCallback.onError();
//                }
//            }
//
//            if (realResponse == null) {
//                Log.w(LOG_TAG, "realResponse is null.");
//                if (downloadedBlock.size() > 0) {
//                    String rangeParam = mRequest.getFirstHeader("Range") == null ? null : mRequest.getFirstHeader("Range").getValue();
//                    Log.d(LOG_TAG, "rangeparam" + rangeParam);
//                    if (!TextUtils.isEmpty(rangeParam)) {
//                        String range = rangeParam.substring(6);
//                        int index = range.indexOf('-');
//                        if (index > 0) {
//                            try {
//                                String startStr = range.substring(0, index);
//                                startFrom = Long.parseLong(startStr);
//                                Log.e(LOG_TAG, "====== range: " + startFrom);
//                            } catch (NumberFormatException nfe) {
//                                Log.w(LOG_TAG, nfe);
//                            }
//                        }
//                    }
//
//                    // 有数据已经下载好了创建模拟数据
//                    StringBuilder httpString = new StringBuilder();
//                    httpString.append("HTTP/1.1 206 Partial Content");
//                    httpString.append("\n");
//                    httpString.append("Date: Sun, 29 Mar 2015 07:41:25 GMT").append("\n");
//                    httpString.append("Server: nginx/1.4.4").append("\n");
//                    httpString.append("Content-Type: video/mp4").append("\n");
//                    httpString.append("Accept-Ranges: bytes").append("\n");
//                    httpString.append("Access-Control-Allow-Origin: *").append("\n");
//                    httpString.append("Access-Control-Max-Age: 2592000").append("\n");
//                    httpString.append("Content-Disposition: inline; filename=\"5517ac4679dbe8010.mp4\"").append("\n");
//                    httpString.append("Content-Transfer-Encoding: binary").append("\n");
//                    httpString.append("ETag: \"ltU-JTZbZ09azl8vsGyEC8YO3DuH\"").append("\n");
//                    httpString.append("X-Log: mc.g;IO").append("\n");
//                    httpString.append("X-Reqid: tQ4AADZNZu8a6M8T").append("\n");
//                    httpString.append("X-Whom: nb263").append("\n");
//                    httpString.append("X-Qiniu-Zone: 0").append("\n");
//                    httpString.append("Content-Range: bytes ").append(startFrom)
//                            .append("-6709786/6709787").append("\n");
//                    httpString.append("Content-Length: ").append(mFileSize - startFrom).append("\n");
//                    httpString.append("Age: 1").append("\n");
//                    httpString.append("X-Via: 1.1 zhj190:8080 (Cdn Cache Server V2.0), 1.1 zhj74:8107 (Cdn Cache Server V2.0), 1.1 fzh194:2 (Cdn Cache Server V2.0)").append("\n");
//                    httpString.append("Connection: close").append("\n");
//                    httpString.append("\n");
//
////                    Log.d(LOG_TAG, "result: \n" + httpString.toString());
//
//                    byte[] buffer = httpString.toString().getBytes();
////                    Log.d(LOG_TAG, "writing to client " + httpString.toString());
//                    mClient.getOutputStream().write(buffer, 0, buffer.length);
//                    mClient.getOutputStream().flush();
//
//                    Long size = downloadedBlock.get(new Long(0));
//                    if (size != null) {
//                        // 说明从0开始的下载快已经有了
//                        if (size.longValue() >= mFileSize) {
//                            // 说明数据已经够实用了。可以直接从文件里面获取数据
//                            randomAccessFile = new RandomAccessFile(getTempCacheFile().getAbsolutePath(), "rw");
//                            randomAccessFile.setLength(size);
//                            randomAccessFile.seek(startFrom);
//                            just_write_file_data = true;
//                        }
//                    }
//
//                    return null;
//                }
//            }
//
//            if (!isRunning) {
//                Log.w(LOG_TAG, "isRunning is false.");
//                return null;
//            }
//
//            Log.d(LOG_TAG, "downloading...");
//
//            try {
//                data = realResponse.getEntity().getContent();
//            } catch (IOException e) {
//                e.printStackTrace();
//                return null;
//            }
//
//            StatusLine line = realResponse.getStatusLine();
//            HttpResponse response = new BasicHttpResponse(line);
//            response.setHeaders(realResponse.getAllHeaders());
//
//            String rangeParam = mRequest.getFirstHeader("Range") == null ? null : mRequest.getFirstHeader("Range").getValue();
//            Log.d(LOG_TAG, "rangeparam" + rangeParam);
//            if (!TextUtils.isEmpty(rangeParam)) {
//                String range = rangeParam.substring(6);
//                int index = range.indexOf('-');
//                if (index > 0) {
//                    try {
//                        String startStr = range.substring(0, index);
//                        startFrom = Long.parseLong(startStr);
//                        Log.e(LOG_TAG, "====== range: " + startFrom);
//                    } catch (NumberFormatException nfe) {
//                        Log.w(LOG_TAG,nfe);
//                    }
//                }
//            }
//
//            // 获取文件长度
//            long fileSize = realResponse.getEntity().getContentLength();
//            if (startFrom == 0) {
//                mFileSize = fileSize;
//            } else {
//                // fileSize just offset of content.
//            }
//
//            // 判断数据是否已经落在文件缓冲中
//            Long size = downloadedBlock.get(new Long(0));
//            if (size != null) {
//                // 说明从0开始的下载快已经有了
//                if (size.longValue() >= startFrom+fileSize) {
//                    // 说明数据已经够实用了。可以直接从文件里面获取数据
//                    randomAccessFile = new RandomAccessFile(getTempCacheFile().getAbsolutePath(), "rw");
//                    randomAccessFile.setLength(size);
//                    randomAccessFile.seek(startFrom);
//                    just_write_file_data = true;
//                }
//            } else {
//                // 还没下载过
//                // 创建文件句柄
//                try {
//                    randomAccessFile = new RandomAccessFile(getTempCacheFile().getAbsolutePath(), "rw");
//                    randomAccessFile.setLength(mFileSize);
//                    randomAccessFile.seek(startFrom);
//                    just_write_file_data = false;
//                } catch (FileNotFoundException e) {
//                    e.printStackTrace();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            }
//
//            Log.d(LOG_TAG, "reading headers");
//            StringBuilder httpString = new StringBuilder();
//            httpString.append(response.getStatusLine().toString());
//
//            httpString.append("\n");
//            for (Header h : response.getAllHeaders()) {
//                httpString.append(h.getName()).append(": ").append(h.getValue()).append("\n");
//            }
//            httpString.append("\n");
//            Log.d(LOG_TAG, "headers done");
//
//            byte[] buffer = httpString.toString().getBytes();
//            int readBytes = -1;
//            Log.d(LOG_TAG, "writing to client " + httpString.toString());
//            mClient.getOutputStream().write(buffer, 0, buffer.length);
//            mClient.getOutputStream().flush();
//
//            return null;
//        }
//
//        @Override
//        public void run() {
////            try {
////                startThread();
////            } catch (IOException e) {
////                e.printStackTrace();
////            }
////            // Start streaming content.
////            byte[] buff = new byte[65535];
////            int readBytes = -1;
////
////            try {
////                if (!just_write_file_data) {
////                    readBytes = data.read(buff, 0, buff.length);
////                } else {
////                    readBytes = randomAccessFile.read(buff, 0, buff.length);
////                    Log.d(LOG_TAG, "randomAccessFile.read " + startFrom + " total ! readBytes " + readBytes + " Thread id " + Thread.currentThread().getId());
////                }
////            } catch (Exception e) {
////                if (null != proxyCallback) {
////                    proxyCallback.onNetError();
////                }
////                Log.w(LOG_TAG,e);
////            }
////            while (isRunning && readBytes != -1) {
////                // while ((readBytes = data.read(buff, 0, buff.length)) != -1) {
////
////                // 先写本地文件保证数据安全，再写入mediaplayer
////                if (!mIsComplete && !just_write_file_data) {
////                    final int writeBytes = readBytes;
////                    // 异步写入
////                    // final byte[] savedBuff = new byte[writeBytes];
////                    // System.arraycopy(buff, 0, savedBuff, 0, writeBytes);
////                    // writeFile(false, randomAccessFile, downloadBlock, savedBuff, writeBytes);
////                    // 同步写入
//////                    writeFile(true, randomAccessFile, downloadBlock, buff, writeBytes);
////                    try {
////                        randomAccessFile.write(buff, 0, writeBytes);
////                    } catch (IOException e) {
////                        e.printStackTrace();
////                        break;
////                    }
////                }
////
////                OutputStream outputStream = null;
////                try {
////                    outputStream = mClient.getOutputStream();
////                    outputStream.write(buff, 0, readBytes);
////                    write_total += readBytes;
////                    Log.d(LOG_TAG, "startFrom " + startFrom + " total ! write_total " + write_total + " Thread id " + Thread.currentThread().getId());
////                    outputStream.flush();
////                } catch (IOException e) {
////                    Log.e(LOG_TAG, "startFrom " + startFrom + " Thread id " + Thread.currentThread().getId() + " error: " + e);
////                    break;
////                }
////
////                try {
////                    if (!just_write_file_data) {
////                        readBytes = data.read(buff, 0, buff.length);
////                    } else {
////                        readBytes = randomAccessFile.read(buff, 0, buff.length);
////                    }
////                } catch (Exception e) {
////                    if (null != proxyCallback) {
////                        proxyCallback.onNetError();
////                    }
////                    Log.w(LOG_TAG,e);
////                }
////            }
////
////            try {
////                if (!just_write_file_data)
////                    downloadedBlock.put(startFrom, write_total);
////                randomAccessFile.close();
////            } catch (IOException e) {
////                e.printStackTrace();
////            }
////            mgr.shutdown();
////        }
//    }
//
//    private void processRequest(HttpRequest request, Socket client)
//        throws IllegalStateException, IOException {
//        if (request == null) {
//            return;
//        }
//
//        tempCacheDir = new File("/mnt/sdcard");
//
//        long count = 0;
//        try {
////            DownloadThread downloadThread = new DownloadThread(request, client);
////            downloadThread.run();
//
//        } catch (Exception e) {
//            Log.w(LOG_TAG, e.getMessage(), e);
//        } finally {
//            client.close();
//            Log.d(LOG_TAG, "streaming complete");
//        }
//    }
//
//    private void writeFile(boolean sync, final RandomAccessFile randomAccessFile, final DownloadBlock downloadBlock,
//        final byte[] savedBuff, final int writeBytes) {
//
//        if (null == randomAccessFile) {
//            Log.w(LOG_TAG, "randomAccessFile is null . ");
//            return;
//        }
//
//        if (sync) {
//            internalWriteFile(randomAccessFile, downloadBlock, savedBuff, writeBytes);
//        } else {
//            pool.execute(new Runnable() {
//
//                @Override
//                public void run() {
//                    internalWriteFile(randomAccessFile, downloadBlock, savedBuff, writeBytes);
//                }
//            });
//        }
//    }
//
//    private synchronized void internalWriteFile(RandomAccessFile randomAccessFile, final DownloadBlock downloadBlock,
//        final byte[] savedBuff, final int writeBytes) {
//        if (mIsComplete) {
//            return;
//        }
//
//        try {
//            randomAccessFile.write(savedBuff, 0, writeBytes);
//            downloadBlock.setBlockSize(downloadBlock.getBlockSize() + writeBytes);
//            if (downloadBlock.isDownloadComplete()) {
//                addDownloadBlock(downloadBlock);
//                saveCachedFile();
//            }
//        } catch (Exception e) {
//            Log.w(LOG_TAG,e);
//        }
//    }
//
//    private File getTempCacheFile() {
//        return new File(tempCacheDir, md5hash(url));
//    }
//
//    static String md5hash(String key) {
//        MessageDigest hash = null;
//        try {
//            hash = MessageDigest.getInstance("MD5");
//        } catch (NoSuchAlgorithmException e) {
//            return null;
//        }
//
//        hash.update(key.getBytes());
//        byte[] digest = hash.digest();
//        StringBuilder builder = new StringBuilder();
//        for (int b : digest) {
//            builder.append(Integer.toHexString((b >> 4) & 0xf));
//            builder.append(Integer.toHexString((b >> 0) & 0xf));
//        }
//        // builder.append(".mv");
//        return builder.toString();
//    }
//
//    private void stopDownload(boolean isPlayComplete) {
//
//        // 关闭所有文件句柄
//        synchronized (mRandomAccessFilesList) {
//            for (RandomAccessFile randomAccessFile : mRandomAccessFilesList) {
//                if (null != randomAccessFile) {
//                    try {
//                        randomAccessFile.close();
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
//            mRandomAccessFilesList.clear();
//        }
//
//        pool.shutdown();
//        saveCachedFile();
//        downloadBlocks.clear();
//    }
//
//    private void addDownloadBlock(DownloadBlock downloadBlock) {
//        if (mIsComplete) {
//            return;
//        }
//
//        if (null != downloadBlock) {
//            long blockSize = 0;
//            DownloadBlock existBlock = downloadBlocks.remove(downloadBlock.getStart());
//            if (null != existBlock) {
//                blockSize = existBlock.getBlockSize();
//                if (blockSize > downloadBlock.getBlockSize()) {
//                    downloadBlock.setBlockSize(blockSize);
//                }
//            }
//            downloadBlocks.put(downloadBlock.getStart(), downloadBlock);
//            synchronized (blockStartPositions) {
//                blockStartPositions.add(downloadBlock.getStart());
//            }
//        }
//    }
//
    private class DownloadBlock {
        private long start = 0;
        private long blockSize = 0;
        private boolean downloadComplete;

        public long getStart() {
            return start;
        }

        public void setStart(long start) {
            this.start = start;
        }

        public long getBlockSize() {
            return blockSize;
        }

        public void setBlockSize(long blockSize) {
            this.blockSize = blockSize;
        }

        public boolean isDownloadComplete() {
            return downloadComplete;
        }

        public void setDownloadComplete(boolean downloadComplete) {
            this.downloadComplete = downloadComplete;
        }

    }
//
//    private boolean checkFileDownloadComplete() {
//        boolean isComplete = false;
//
//        if (null == blockStartPositions || blockStartPositions.isEmpty()) {
//            return isComplete;
//        }
//
//        try {
//            Collections.sort(blockStartPositions);
//        } catch (Exception e) {
//            Log.w(LOG_TAG,e);
//            return isComplete;
//        }
//        if (blockStartPositions.get(0) != 0) {
//            return isComplete;
//        }
//
//        long blockStart = 0;
//        long blockSize = 0;
//        // TODO ConcurrentModificationException http://zhoujianghai.iteye.com/blog/1041555
//        synchronized (blockStartPositions) {
//            for (Long startPosition : blockStartPositions) {
//                DownloadBlock downloadBlock = downloadBlocks.get(startPosition);
//                if (null != downloadBlock && downloadBlock.getStart() >= blockStart
//                    && downloadBlock.getStart() <= blockSize) {
//                    blockSize = downloadBlock.getBlockSize() + blockSize - (blockSize - downloadBlock.getStart());
//                }
//            }
//        }
//
//        if (blockSize == mFileSize) {
//            Log.v(LOG_TAG, "set complete ok success !" + "  blockSize is [" + blockSize + "]");
//            isComplete = true;
//        }
//
//        return isComplete;
//    }
//
//    private void saveCachedFile() {
//        if (TextUtils.isEmpty(url) || !checkFileDownloadComplete()) {
//            return;
//        }
//        saveCacheDir = new File("/mnt/sdcard/");
//        File file = new File(saveCacheDir, md5hash(url));
//        if (null != file && null != file.getParentFile() && !file.getParentFile().exists()) {
//            boolean reMkDirs = file.getParentFile().mkdirs();
//            if (!reMkDirs) {
//                Log.d(LOG_TAG,"save Foler not exits, reMkDirs = " + reMkDirs);
//            }
//        }
//        File cacheFile = getTempCacheFile();
//        boolean result = cacheFile.renameTo(file);
//        if (result) {
//            Log.d(LOG_TAG,"Set file write complete success !");
//            mIsComplete = true;
//        } else {
//            Log.w(LOG_TAG,"remove video file to save Foler failed!");
//        }
//    }
//
}
