package tv.danmaku.ijk.media.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.StringTokenizer;

import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

/**
 * 提供视频边下边播的httpServer；
 * @author Owen
 *
 */
public class HttpServer {

    private static final String TAG = HttpServer.class.getSimpleName();

    // 本地测试文件
    private static final String LOCAL_TEST_FILE_PATH = Environment.getExternalStorageDirectory()
        + "/5517ac4679dbe8010.mp4";

    private static HttpServer mHttpServer = null;
    private ServerSocket mServerSocket;
    private int port = 0;

    public static int BUFFER_SIZE_SERVER_TRANS = 1024 * 1024;

    public static HttpServer getInstance() {
        if (null == mHttpServer) {
            mHttpServer = new HttpServer();
        }
        return mHttpServer;
    }

    public HttpServer() {
        Log.d(TAG, "init HttpServer");
        try {
            mServerSocket = new ServerSocket(0, 0, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            mServerSocket.setSoTimeout(5000);
            port = mServerSocket.getLocalPort();
            new Thread(new Runnable() {

                @Override
                public void run() {
                    while (true) {
                        try {
                            final Socket requestSocket = mServerSocket.accept();
                            new Thread(new Runnable() {

                                @Override
                                public void run() {
                                    transByte2Socket(requestSocket, Long.valueOf(parseSocketRequest(requestSocket)[1]));
                                }
                            }).start();
                        } catch (IOException e) {
                            // Log.d(TAG, "ServerSocket accept time out");
                        }
                    }
                }
            }, "thread_HttpServer_Listener").start();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 解析socket请求到达的http格式，解析出url和range头
     * @param socket
     * @return
     */
    private String[] parseSocketRequest(Socket socket) {
        String[] rst = {"", ""};
        InputStream is;
        String firstLine = null;
        String range = null;
        String host = null;
        try {
            is = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is), 1024);
            firstLine = reader.readLine();
            String line = null;
            do {
                line = reader.readLine();
                System.out.println("line : " + line);
                if (line != null && line.toLowerCase().startsWith("range: ")) {
                    range = line.substring(7);
                }
                if (line != null && line.toLowerCase().startsWith("host: ")) {
                    host = line.substring(6);
                }
            } while (!TextUtils.isEmpty(line) && reader.ready());
        } catch (IOException e) {
            Log.e(TAG, "Error parsing request", e);
        }

        if (firstLine == null) {
            Log.i(TAG, "Proxy client closed connection without a request.");
            return rst;
        }

        // 解析生成结果
        StringTokenizer st = new StringTokenizer(firstLine);
        String method = st.nextToken();
        String uri = st.nextToken();
        String realUri = uri.substring(1);
        rst[0] = realUri.trim();
        String r = range.trim().substring(6).trim();
        if (r.contains("-")) {
            if (r.endsWith("-")) {
                rst[1] = r.replace('-', ' ').trim();
            } else {
                rst[1] = r.split("-")[0];
            }
        } else {
            rst[1] = r;
        }
        Log.d(TAG, "URL :" + rst[0] + "  Range :" + rst[1]);
        return rst;
    }

    private void transByte2Socket(final Socket socket, final long range) {

        RandomAccessFile randomAccessFile = null;

        try {
            randomAccessFile = new RandomAccessFile(LOCAL_TEST_FILE_PATH, "rw");

            // 1、先响应http状态
            Log.d(TAG, Thread.currentThread().getName() + "----------TransByte2Socket Start----------");
            StringBuilder header = new StringBuilder();
            header.append("HTTP/1.1 206 Partial Content").append('\n');
            header.append("Date: Sun, 29 Mar 2015 07:41:25 GMT").append('\n');
            header.append("Server: nginx/1.4.4").append('\n');
            header.append("Content-Type: video/mp4").append('\n');
            header.append("Accept-Ranges: bytes").append('\n');
            header.append("Access-Control-Allow-Origin: *").append('\n');
            header.append("Accept-Ranges: bytes").append('\n');
            header.append("Cache-Control: public, max-age=31536000").append('\n');
            header.append("Content-Disposition: inline; filename=\"5517ac4679dbe8010.mp4\"").append('\n');
            header.append("Content-Transfer-Encoding: binary").append('\n');
            header.append("ETag: \"ltU-JTZbZ09azl8vsGyEC8YO3DuH\"").append('\n');
            header.append("X-Log: mc.g;IO").append('\n');
            header.append("X-Reqid: tQ4AADZNZu8a6M8T").append('\n');
            header.append("X-Whom: nb263").append('\n');
            header.append("X-Qiniu-Zone: 0").append('\n');
            header.append("X-Whom: nb263").append('\n');
            header.append("Content-Range: bytes ")
                .append(range)
                .append("-")
                .append(randomAccessFile.length())
                .append("/")
                .append(randomAccessFile.length())
                .append('\n');
            header.append("Content-Length: ").append(randomAccessFile.length() - range).append('\n');
            header.append("Age: 1").append('\n');
            header.append(
                "X-Via: 1.1 zhj190:8080 (Cdn Cache Server V2.0), 1.1 zhj74:8107 (Cdn Cache Server V2.0), 1.1 fzh194:2 (Cdn Cache Server V2.0)")
                .append('\n');
            header.append("Connection: close").append('\n');
            header.append('\n');

            System.out.println(Thread.currentThread().getName() + "======================================");
            System.out.println(header.toString());
            System.out.println(Thread.currentThread().getName() + "======================================");

            byte[] buffer = header.toString().getBytes();
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(buffer, 0, buffer.length);
            outputStream.flush();
            // 2、响应文件体

            randomAccessFile.seek(Long.valueOf(range));
            System.out.println(Thread.currentThread().getName() + "  seek : " + range);
            byte buf[] = new byte[BUFFER_SIZE_SERVER_TRANS];
            int read = -1;
            do {
                read = randomAccessFile.read(buf);
                outputStream.write(buf);
                outputStream.flush();
            } while (read > 0);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                randomAccessFile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Log.d(TAG, Thread.currentThread().getName() + "----------TransByte2Socket Complete----------");
        }

    }

    public int getPort() {
        return port;
    }

}
