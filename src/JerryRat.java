import http.EntityBody;
import http.Response1_0;
import http.ResponseHead;
import http.StatusLine;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class JerryRat implements Runnable {

    public static final String SERVER_PORT = "8080";
    public static final String WEB_ROOT = "res/webroot";
    public static final String HTTP_VERSION = "HTTP/";
    public static final Integer TIME_OUT = 10000;
    public static final String SERVER = "JerryRat/1.0 (Linux)";
    public static final String STATUS200 = "200 OK";
    public static final String STATUS201 = "201 Created";
    public static final String STATUS204 = "204 No Content";
    public static final String STATUS301 = "301 Moved Permanently";
    public static final String STATUS400 = "400 Bad Request";
    public static final String STATUS401 = "401 Unauthorized";
    public static final String STATUS403 = "403 Forbidden";
    public static final String STATUS404 = "404 Not Found";
    public static final String STATUS501 = "501 Not Implemented";
    public Map<String, String> map;


    ServerSocket serverSocket;

    public JerryRat() throws IOException {
        serverSocket = new ServerSocket(Integer.parseInt(SERVER_PORT));
    }

    @Override
    public void run() {
        app:
        while (true) {
            try (
                    Socket clientSocket = serverSocket.accept();
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            ) {
                clientSocket.setSoTimeout(TIME_OUT);
                String request = in.readLine();
                Response1_0 response = simpleResponse(STATUS200);

                String requestMethod = "";
                String requestURL = null;
                String authorization = null;
                String basic = null;
                int requestContentLength = -1;

                String[] req = request.split(" ");
                if (req.length <= 1) {
                    response = simpleResponse(STATUS400);
                    out.print(response);
                    out.flush();
                    continue;
                }
                String requestHead = req[0];
                requestURL = req[1];
                requestURL = URLDecoder.decode(requestURL, StandardCharsets.UTF_8);

                //请求方法
                switch (requestHead) {
                    case "GET":
                    case "HEAD":
                        requestMethod = requestHead;
                        if (requestURL.startsWith("/endpoints/redirect")) {
                            requestURL = "http://localhost/";
                            response.getStatusLine().setStatusCode(STATUS301);
                            response.getResponseHead().setLocation(requestURL);
                            break;
                        }
                        //400
                        if (req.length >= 3 && !req[req.length - 1].toUpperCase(Locale.ROOT).startsWith(HTTP_VERSION)) {
                            response.getStatusLine().setStatusCode(STATUS400);
                            break;
                        }

                        //404
                        File requestFile = new File(WEB_ROOT + requestURL);
                        requestFile = getFileName(requestFile);
                        if (!requestFile.exists()) {
                            response.getStatusLine().setStatusCode(STATUS404);
                            break;
                        }

                        //GET 返回body
                        response = GETMethodResponse(requestFile, getRequestFileType(requestFile));
                        if (requestMethod.equals("GET")) {
                            //HTTP 0.9
                            if (!req[req.length - 1].toUpperCase(Locale.ROOT).startsWith(HTTP_VERSION)) {
                                response = new Response1_0();
                                response.setEntityBody(new EntityBody(getFileContent(requestFile)));
                                out.print(response);
                                out.flush();
                                continue app;
                            }
                            EntityBody entityBody = new EntityBody<>(new String(getFileContent(requestFile), StandardCharsets.UTF_8));
                            response.setEntityBody(entityBody);
                        }
                        break;
                    case "POST":
                        requestMethod = requestHead;
                        break;
                    default:
                        response.getStatusLine().setStatusCode(STATUS501);
                        break;
                }
                request = in.readLine();
                label:
                while (request != null) {
                    req = request.split(" ");
                    requestHead = req[0];
                    if (request.equals("")) {
                        if (requestMethod.equals("POST")) {
                            if (requestContentLength <= 0) {
                                response.getStatusLine().setStatusCode(STATUS400);
                                break;
                            }
                            if (requestURL.equals("/endpoints/null")) {
                                response = POSTMethodResponse(in, requestURL, requestContentLength);
                                break;
                            } else if (requestURL.startsWith("/emails")) {
                                File dir = new File(WEB_ROOT, "/emails");
                                if (!dir.exists()) {
                                    dir.mkdirs();
                                }
                                File postFile = new File(WEB_ROOT, requestURL);
                                if (!postFile.exists()) {
                                    postFile.createNewFile();
                                }
                                response = POSTMethodResponse(in, requestURL, requestContentLength);
                            }
                        } else if (requestURL.equals("/secret.txt")) {
                            if (authorization == null || (basic != null && !basic.equals("Basic"))) {
                                response = simpleResponse(STATUS401);
                                response.setEntityBody(null);
                                response.getResponseHead().setWWWAuthenticate("Basic realm=\"adalab\"");
                            } else if ((basic != null)&&!authorization.equals("hello:world")) {
                                response = simpleResponse(STATUS403);
                                response.setEntityBody(null);
                            }
                        }
                        break;
                    }
                    switch (requestHead) {
                        case "User-Agent:":
                            if (requestMethod.equals("GET") && requestURL.equals("/endpoints/user-agent")) {
                                String fieldValue = request.substring(12);
                                response.getStatusLine().setStatusCode(STATUS200);
                                response.getResponseHead().setContentLength(fieldValue.getBytes().length);
                                response.setEntityBody(new EntityBody(request.substring(12)));
                            }
                            break;
                        case "Content-Length:":
                            requestContentLength = Integer.parseInt(req[1]);
                            break;
                        case "Authorization:":
                            basic = req[1];
                            authorization = new String(Base64.getDecoder().decode(req[2]), StandardCharsets.UTF_8);
                            break;
                        default:
                            break;
                    }
                    request = in.readLine();
                }
                System.out.println(response.getStatusLine().getStatusCode());
                out.print(response);
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
                System.err.println("TCP连接错误！");
            }
        }

    }

    private Response1_0 POSTMethodResponse(BufferedReader in, String requestURL, int requestContentLength) throws IOException {
        char[] chars = new char[requestContentLength];
        in.read(chars);
        if (requestURL.startsWith("/emails")) {
            FileWriter fis = new FileWriter(WEB_ROOT + requestURL);
            BufferedWriter bw = new BufferedWriter(fis);
            bw.write(chars);
            bw.flush();
            bw.close();
            return simpleResponse(STATUS201);
        } else if (requestURL.equals("/endpoints/null")) {
            return simpleResponse(STATUS204);
        }
        return simpleResponse(STATUS200);
    }

    private String getRequestFileType(File requestFile) {
        String[] urls = requestFile.getName().split("\\.");
        int length = urls.length;
        if (length > 1) {
            return urls[length - 1];
        }
        return "";
    }

    private File getFileName(File requestFile) {
        if (requestFile.isDirectory()) {
            requestFile = new File(requestFile, "/index.html");
        }
        return requestFile;
    }

    private Response1_0 GETMethodResponse(File requestFile, String contentType) throws IOException {
        StatusLine statusLine = new StatusLine();
        ResponseHead responseHead = new ResponseHead();
        Response1_0 response;
        EntityBody entityBody = null;
        contentType = getContentType("." + contentType);
        //Status-Line
        statusLine.setStatusCode(STATUS200);
        //Date头
        responseHead.setDate(new Date());
        //Server头
        responseHead.setServer(SERVER);
        //Content-Length头
        responseHead.setContentLength(requestFile.length());
        //Content-Type头
        responseHead.setContentType(contentType);
        //Last-Modified头
        responseHead.setLastModified(requestFile.lastModified());
        //EntityBody
        response = new Response1_0(statusLine, responseHead, entityBody);
        return response;
    }

    private byte[] getFileContent(File requestFile) throws IOException {
        FileInputStream fos = new FileInputStream(requestFile);
        BufferedInputStream bis = new BufferedInputStream(fos);
        byte[] content = new byte[(int) requestFile.length()];
        bis.read(content);
        bis.close();
        return content;

    }

    private String getContentType(String content) throws IOException {
        map = new HashMap<>();
        try (
                FileReader fos = new FileReader("res/contentType.txt");
                BufferedReader br = new BufferedReader(fos)
        ) {
            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                String[] lines = line.split(" ");
                for (int i = 0; i < lines.length; i += 2) {
                    map.put(lines[i].strip(), lines[i + 1].strip());
                }
            }
            if (map.get(content) == null) {
                return "application/octet-stream";
            }
            return map.get(content);
        }

    }

    private Response1_0 simpleResponse(String statusCode) {
        StatusLine statusLine = new StatusLine();
        Response1_0 response1_0 = new Response1_0();
        statusLine.setStatusCode(statusCode);

        ResponseHead responseHead = new ResponseHead();
        responseHead.setServer(SERVER);
        responseHead.setContentLength(0);
//        responseHead.setContentType("text/plain; charset=utf-8");

        response1_0.setStatusLine(statusLine);
        response1_0.setResponseHead(responseHead);
        return response1_0;
    }

    public static void main(String[] args) throws IOException {
        JerryRat jerryRat = new JerryRat();
        new Thread(jerryRat).start();
    }
}
