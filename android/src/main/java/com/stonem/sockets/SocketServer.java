package com.stonem.sockets;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;

import android.util.Log;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.util.SparseArray;

import org.json.JSONObject;
import org.json.JSONStringer;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;
import java.nio.charset.Charset;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.UnknownHostException;

/**
 * Created by David Stoneham on 2017-08-03.
 */
public class SocketServer {
    public ServerSocket serverSocket;

    private final String eTag = "NATIVE-SOCKETS-server";
    private final String event_closed = "ClosedServer";
    private final String event_data = "Server_Rev_data";
    private final String event_error = "Server_error";
    private final String event_connect = "StartServer";
    private final String event_clientConnect = "ClientConnected";
    private final String event_clientDisconnect = "ClientDisconnected";
    private static byte[] setConfigHeader = {(byte)0x01, (byte)0x01, (byte)0x42 };
    private SparseArray<Object> mClients = new SparseArray<Object>();
    private int socketServerPORT;
    private ReactContext mReactContext;
    private boolean isOpen = false;
    private final byte EOT = 0x04;

    public SocketServer(int port, ReactContext reactContext) {
        mReactContext = reactContext;
        socketServerPORT = port;
        Thread socketServerThread = new Thread(new SocketServerThread());
        socketServerThread.start();
    }

    public void close() {
        try {
            if (serverSocket != null) {
                isOpen = false;
                for (int i = 0; i < mClients.size(); i++) {
                    int key = mClients.keyAt(i);
                    Object socket = mClients.get(key);
                    if (socket != null && socket instanceof Socket) {
                        try {
                            ((Socket) socket).close();
                        } catch (IOException e) {
                            handleIOException(e);
                        }
                    }
                }
                serverSocket.close();
                serverSocket = null;
                Log.d(eTag, "server closed");
            }
        } catch (IOException e) {
            handleIOException(e);
        }
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }

    public void onDestroy() {
        if (serverSocket != null) {
            close();
        }
    }

    public void write(String message, int cId, int cmdType) {
        new AsyncTask<Object, Void, Void>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
            }

            @Override
            protected Void doInBackground(Object... params) {
                int cId = (int) params[0];
                String message = (String) params[1];
                int cmd = (int) params[2];
                Object socket = mClients.get(cId);

                byte[] pkg = null;
                if (socket != null && socket instanceof Socket) {
                    try {
                        DataOutputStream outputStream = new DataOutputStream(((Socket) socket).getOutputStream());
                        byte[] sendData = message.toString().getBytes();
                        pkg = new byte[3 + 4 + sendData.length + 2];
                        Log.d(eTag, "server sent message00: " + sendData.length);
                        if(cmd == 42){
                            setConfigHeader[2] = (byte)0x42;
                        }else if(cmd == 43){
                            setConfigHeader[2] = (byte)0x43;
                        }else if(cmd == 44){
                            setConfigHeader[2] = (byte)0x44;
                        }
                        System.arraycopy(setConfigHeader, 0, pkg, 0, 3);
                        System.arraycopy(IntToByte4(sendData.length), 0, pkg, 3, 4);
                        System.arraycopy(sendData, 0, pkg, 7, sendData.length);

                        pkg[pkg.length - 2] = crc16(pkg)[0];
                        pkg[pkg.length - 1] = crc16(pkg)[1];

                        outputStream.write(pkg);
                        outputStream.flush();
//                        outputStream.close();

                        Log.d(eTag, "server sent message: " + message);
                    } catch (Exception e) {
                        Log.d(eTag, "server sent msg: " + e.toString());
                        handleIOException(e);
                    }
                }
                return null;
            }

            protected void onPostExecute(Void dummy) {
            }
        }.execute(cId, message, cmdType);
    }

    private class SocketServerThread extends Thread {
        int count = 0;

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(socketServerPORT);

                isOpen = true;
                WritableMap eventParams = Arguments.createMap();
                sendEvent(mReactContext, event_connect, eventParams);

                while (isOpen) {
                    Socket socket = serverSocket.accept();  socket.setSoTimeout(1800000);
                    count++;
                    for (int i = 0; i < mClients.size(); i++) {
                        int key = mClients.keyAt(i);
                        Object skt = mClients.get(key);
                        if (skt != null && skt instanceof Socket) {
                            try {
                                ((Socket) skt).close();
                            } catch (IOException e) {
                                handleIOException(e);
                            }
                        }
                    }
                    mClients.put(socket.getPort(), socket);

                    eventParams = Arguments.createMap();
                    eventParams.putInt("id", socket.getPort());
                    eventParams.putString("ip", socket.getInetAddress().toString());

                    sendEvent(mReactContext, event_clientConnect, eventParams);

                    Log.d(eTag, "#" + count + " from " + socket.getInetAddress() + ":" + socket.getPort());

                    Thread socketServerReplyThread = new Thread(new SocketServerReplyThread(socket));
                    socketServerReplyThread.start();
                }
            } catch (IOException e) {
                handleIOException(e);
            }
        }

    }

    private class SocketServerReplyThread extends Thread {
        private Socket hostThreadSocket;
        private int cId;
        private boolean clientConnected = true;

        SocketServerReplyThread(Socket socket) {
            hostThreadSocket = socket;
            cId = hostThreadSocket.getPort();
        }

        @Override
        public void run() {
            try {
                byte[] receiveData=null;
                byte cmdType = 0x00;
                int dataLen = 0, len=0, readLen = 0, needDataLen = 0;
                byte[] buffer = new byte[7];
                InputStream inputStream = hostThreadSocket.getInputStream();
                while (isOpen && clientConnected) {
//                    dataLen = 0;  readLen = 0; needDataLen = 0;

                    if(readLen >=0 && readLen < 7){
                        readLen += inputStream.read(buffer, readLen, 7-readLen);
                        len=0;
                    }else{

                        System.out.println("=====header======"+readLen+"=="+buffer[0]+"="+buffer[1]+"=="+buffer[2]);
                        if(((buffer[0]==0x01) && (buffer[1]==0x02) && (buffer[2]<0x4F))){

                            dataLen = byteToint(buffer, 3);
                            System.out.println("==Super==dataLen=" + dataLen+"=="+buffer[0]+"="+buffer[1]+"=="+buffer[2]);
                            receiveData = new byte[dataLen+9];
                            System.arraycopy(buffer, 0, receiveData, 0, 7);
                            needDataLen = dataLen + 2;
                            while(needDataLen > 0){
                                readLen = inputStream.read(receiveData, dataLen+2+7-needDataLen, needDataLen);
                                System.out.println("=needDataLen=" + needDataLen + "=readLen=" + readLen);
                                needDataLen = needDataLen - readLen;
                            }
                            dataLen=receiveData.length;

                            if (dataLen > 0) {
                                while (len < dataLen) {
                                    System.out.println("===SocketREV==="+receiveData[len]+"===="+receiveData[len + 1]+"=="+receiveData[len + 2]+"=="+len+"=datalen="+dataLen);
                                    if((receiveData[len] == 0x01) && (receiveData[len + 1] == 0x02) && (receiveData[len + 2] == 0x42))
                                    {
                                        int tmp =  byteToint(receiveData, len+3);
                                        byte[] ReSuperData = new byte[7 + tmp + 2];
                                        System.arraycopy(receiveData, len, ReSuperData, 0, ReSuperData.length);
                                        if ( crc16(ReSuperData)[0] == ReSuperData[ReSuperData.length - 2] &&  crc16(ReSuperData)[1] == ReSuperData[ReSuperData.length - 1]) {

                                            String reValue = new String(ReSuperData, 7, tmp);

                                            WritableMap eventParams = Arguments.createMap();
                                            eventParams.putInt("client", cId);
                                            eventParams.putString("data", (reValue));
                                            eventParams.putInt("cmd", receiveData[len + 2]);
                                            sendEvent(mReactContext, event_data, eventParams);
                                            System.out.println("===SocketREV CMD:=="+reValue+"==len="+len+"=datalen="+dataLen+"==readLen="+readLen);
                                            readLen = 0;
                                            break;
                                        }
                                        else{
                                            len++;
                                            System.out.println("==SocketREV CMD==CRC error="+ReSuperData[ReSuperData.length - 2]+ReSuperData[ReSuperData.length - 1]);

                                            for(int a=0; a<(dataLen-len); a++){
                                                System.out.print(ReSuperData[a]+" ");
                                            }
                                            dataLen = 0;  readLen = 0; needDataLen = 0;
                                            break;
                                        }
                                    }
                                    else{
                                        len++;
                                        if((dataLen -len)< 9){
                                            break;
                                        }
                                    }
                                }
                            }
                        }else{
                            len++;
                            if((dataLen -len)< 9){
                                break;
                            }
                        }

                    }

//                    if (incomingByte == -1) {
//                        clientConnected = false;
//                        //debug log
//                        Log.v(eTag, "Client disconnected");
//                        //emit event
//                        WritableMap eventParams = Arguments.createMap();
//                        eventParams.putInt("client", cId);
//                        sendEvent(mReactContext, event_clientDisconnect, eventParams);
//                    } else if (incomingByte == EOT) {
//
//                        //debug log
//                        Log.d(eTag, "client received message: " + data.length+ "=="+data[1]);
//                        //emit event
//                        WritableMap eventParams = Arguments.createMap();
//                        eventParams.putInt("client", cId);
//                        eventParams.putString("data", data);
//                        sendEvent(mReactContext, event_data, eventParams);
//                        //clear incoming
////                        data = "";
//                        data = null;
//                    } else {
////                        data += (char) incomingByte;
//
//                    }

                }
            } catch (Exception e) {
                Log.e(eTag, "SocketServer err="+e.toString());

                handleIOException(e);
            }
        }

    }

    private void handleIOException(Exception e) {
        //debug log
        Log.e(eTag, "Server IOException", e);
        //emit event
        String message = e.getMessage();
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("error", message);
        if (message != null && message.equals("Socket closed")) {
            sendEvent(mReactContext, event_closed, eventParams);
            isOpen = false;
        } else {
            sendEvent(mReactContext, event_error, eventParams);
//            sendEvent(mReactContext, event_closed, eventParams);
            close();
        }
    }



    private int byteToint(byte b[], int offset) {

        return (b[offset + 3] & 0xFF) | ((b[offset + 2] & 0xFF) << 8) | ((b[offset + 1] & 0xFF) << 16) | ((b[offset]) << 24);
    }

    // int to byte4
    private byte[] IntToByte4(int i) {
        byte[] targets = new byte[4];
        targets[3] = (byte) (i & 0xFF);
        targets[2] = (byte) (i >> 8 & 0xFF);
        targets[1] = (byte) (i >> 16 & 0xFF);
        targets[0] = (byte) (i >>> 24 & 0xFF);
        return targets;
    }

    private byte[] crc16(byte[] data) {

        byte[] temdata = { (byte) 0, (byte) 0 };

        int CRC = 0x0000ffff;
        int POLYNOMIAL = 0x0000a001;
        int i, j;

        int buflen = data.length;

        for (i = 0; i < buflen-2; i++)
        {
            CRC ^= ((int)data[i] & 0x000000ff);
            for (j = 0; j < 8; j++)
            {
                if ((CRC & 0x00000001) != 0)
                {
                    CRC >>= 1;
                    CRC ^= POLYNOMIAL;
                }
                else
                {
                    CRC >>= 1;
                }
            }

        }
        //   System.out.println("===CRC++"+Integer.toHexString(CRC));
        temdata[1] = (byte)(CRC & 0x00ff);
        temdata[0] = (byte)(CRC >> 8);

        return temdata;
    }
 
    
    
/*

public void write(String message, int cId) {
        new AsyncTask<Object, Void, Void>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
            }

            @Override
            protected Void doInBackground(Object... params) {
                int cId = (int) params[0];
                String message = (String) params[1];
                Object socket = mClients.get(cId);
                if (socket != null && socket instanceof Socket) {
                    try {
                        OutputStream outputStream = ((Socket) socket).getOutputStream();
                        PrintStream printStream = new PrintStream(outputStream);
                        printStream.print(message + (char) EOT);
                        printStream.flush();
                        outputStream.flush();

                        Log.d(eTag, "server sent message: " + message);
                    } catch (IOException e) {
                        handleIOException(e);
                    }
                }
                return null;
            }

            protected void onPostExecute(Void dummy) {
            }
        }.execute(cId, message);
    }

private class SocketServerReplyThread extends Thread {
        private Socket hostThreadSocket;
        private int cId;
        private boolean clientConnected = true;

        SocketServerReplyThread(Socket socket) {
            hostThreadSocket = socket;
            cId = hostThreadSocket.getPort();
        }

        @Override
        public void run() {
            try {
                String data = "";
                int p=0;
                InputStream inputStream = hostThreadSocket.getInputStream();
                while (isOpen && clientConnected) {
                    int incomingByte = inputStream.read();

                    if (incomingByte == -1) {
                        clientConnected = false;
                        //debug log
                        Log.v(eTag, "Client disconnected");
                        //emit event
                        WritableMap eventParams = Arguments.createMap();
                        eventParams.putInt("client", cId);
                        sendEvent(mReactContext, event_clientDisconnect, eventParams);
                    } else if (incomingByte == EOT) {

                        //debug log
                        Log.d(eTag, "client received message: " + data.length()+ "=="+data);
                        //emit event
                        WritableMap eventParams = Arguments.createMap();
                        eventParams.putInt("client", cId);
                        eventParams.putString("data", data);
                        sendEvent(mReactContext, event_data, eventParams);
                        //clear incoming
                        data = "";
                    } else {
                        data += (char) incomingByte;
                    }

                }
            } catch (IOException e) {
                handleIOException(e);
            }
        }

    }
 */

}
