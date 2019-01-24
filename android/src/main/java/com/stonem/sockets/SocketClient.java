package com.stonem.sockets;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import android.util.Log;
import android.os.AsyncTask;
import android.support.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Created by David Stoneham on 2017-08-03.
 */

public class SocketClient {
    public Socket clientSocket;


    private final String eTag = "REACT-NATIVE-SOCKETS";
    private final String event_closed = "socketClient_closed";
    private final String event_data = "socketClient_data";
    private final String event_error = "socketClient_error";
    private final String event_connect = "socketClient_connected";
    private String dstAddress;
    private int dstPort;
    private ReactContext mReactContext;
    private boolean isOpen = false;
    private boolean reconnectOnClose = false;
    private int reconnectDelay = 500;
    private int maxReconnectAttempts = -1;
    private int reconnectAttempts = 0;
    private boolean userDidClose = false;
    private boolean isFirstConnect = true;
    private BufferedInputStream bufferedInput;
    private boolean readingStream = false;
    private final byte EOT = 0x04;
    private static byte[] setConfigHeader = {(byte)0x01, (byte)0x01, (byte)0x42 };


    SocketClient(ReadableMap params, ReactContext reactContext) {
        //String addr, int port, boolean autoReconnect
        mReactContext = reactContext;
        dstAddress = params.getString("address");
        dstPort = params.getInt("port");
        if (params.hasKey("reconnect")) {
            reconnectOnClose = params.getBoolean("reconnect");
        }
        if (params.hasKey("maxReconnectAttempts")) {
            maxReconnectAttempts = params.getInt("maxReconnectAttempts");
        }
        if (params.hasKey("reconnectDelay")) {
            reconnectDelay = params.getInt("reconnectDelay");
        }

        Thread socketClientThread = new Thread(new SocketClientThread());
        socketClientThread.start();
    }

    public void disconnect(boolean wasUser) {
        try {
            if (clientSocket != null) {
                userDidClose = wasUser;
                isOpen = false;
                clientSocket.close();
                clientSocket = null;
                Log.d(eTag, "client closed");
            }
        } catch (IOException e) {
            Log.e(eTag, "disconnect", e);
            handleIOException(e);
        }
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }

    protected void write(String message, int cmdType) {
        new AsyncTask<Object, Void, Void>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
            }

            @Override
            protected Void doInBackground(Object... params) {
                try {
                    String message = (String) params[0];
                    int cmd = (int) params[1];
                    OutputStream outputStream = clientSocket.getOutputStream();
//                    OutputStream outputStream = new DataOutputStream(clientSocket.getOutputStream());
//                    PrintStream printStream = new PrintStream(outputStream);
//                    byte[] pkg = null;
                    byte[] sendData = message.toString().getBytes();
                    byte[] pkg = new byte[3 + 4 + sendData.length + 2];
                    Log.d(eTag, "client sent message00: " + sendData.length+"=="+setConfigHeader[2]);
//                    if(cmd == 42){
//                        setConfigHeader[2] = (byte)0x42;
//                    }else if(cmd == 43){
//                        setConfigHeader[2] = (byte)0x43;
//                    }else if(cmd == 44){
//                        setConfigHeader[2] = (byte)0x44;
//                    }
                    setConfigHeader[2] = (byte)(cmd & 0xFF);
                    System.arraycopy(setConfigHeader, 0, pkg, 0, 3);
                    System.arraycopy(IntToByte4(sendData.length), 0, pkg, 3, 4);
                    System.arraycopy(sendData, 0, pkg, 7, sendData.length);

                    pkg[pkg.length - 2] = crc16(pkg)[0];
                    pkg[pkg.length - 1] = crc16(pkg)[1];
                    outputStream.write(pkg);
                    outputStream.flush();
//                    printStream.print(message + (char) EOT);
//                    printStream.print(pkg);
//                    printStream.flush();
                    //debug log
                    for(int p=0; p<pkg.length;p++){
                        message += ' '+Integer.toHexString(pkg[p]);
                    }
                    Log.d(eTag, "client sent message: " + message);
                } catch (IOException e) {
                    Log.e(eTag, "client-write", e);
                    handleIOException(e);
                }
                return null;
            }

            protected void onPostExecute(Void dummy) {
            }
        }.execute(message, cmdType);
    }

    public void onDestroy() {
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                Log.e(eTag, "Client Destroy IOException", e);
            }
        }
    }

    private class SocketClientThread extends Thread {
        @Override
        public void run() {
            while (isFirstConnect || (!userDidClose && reconnectOnClose)) {
                try {
                    if (connectSocket()) {
                        watchIncoming();
                        reconnectAttempts = 0;
                    } else {
                        reconnectAttempts++;
                    }
                    isFirstConnect = false;
                    if (maxReconnectAttempts == -1 || maxReconnectAttempts < reconnectAttempts) {
                        Thread.sleep(reconnectDelay);
                    } else {
                        reconnectOnClose = false;
                    }
                } catch (InterruptedException e) {
                    //debug log
                    Log.e(eTag, "Client InterruptedException", e);
                }
            }
        }
    }

    private boolean connectSocket() {
        try {
            if(clientSocket != null ){
                onDestroy();
            }
            clientSocket = new Socket(dstAddress, dstPort);
            isOpen = true;

//            WritableMap eventParams = Arguments.createMap();
//            sendEvent(mReactContext, event_connect, eventParams);
            return true;
        } catch (UnknownHostException e) {
            handleUnknownHostException(e);
        } catch (IOException e) {
            Log.e(eTag, "connectSocket", e);
            handleIOException(e);
        }
        return false;
    }

    private void watchIncoming() {
        try {
            String data = ""; String reValue="";
            byte[] receiveData=null;
            byte cmdType = 0x00;
            int dataLen = 0, len=0, readLen = 0, needDataLen = 0; int tmp=0;
            byte[] buffer = new byte[7];  byte[] ReSuperData;
            InputStream inputStream = clientSocket.getInputStream();
            System.out.println("===client==watchIncoming======");
            while (isOpen) {
//                    dataLen = 0;  readLen = 0; needDataLen = 0;

                if(buffer[0] == 0x01 && readLen == 1){
                    readLen += inputStream.read(buffer, 1, 1);
                    System.out.println("===client==read111======"+readLen+"="+buffer[0]+"="+buffer[1]);
//                }else if(buffer[0] == 0x01 && buffer[1] == 0x02 && readLen == 2){
                }else if(buffer[0] == 0x01 && (buffer[1] == 0x02 || buffer[1] == 0x01) && readLen == 2){
                    readLen += inputStream.read(buffer, 2, 1);
                    System.out.println("===client==read222======"+readLen+"="+buffer[0]+"="+buffer[1]+"="+buffer[2]);
//                }else if((buffer[0] == 0x01) && (buffer[1] == 0x02) && ((buffer[2] > 60 && buffer[2] < 80)) && readLen == 3){
                }else if(( ((buffer[0] == 0x01) && (buffer[1] == 0x02) && ((buffer[2] > 60 && buffer[2] < 80))) || ((buffer[0] == 0x01) && (buffer[1] == 0x01) && (buffer[2] == 0x02)) ) && readLen == 3){
                    readLen += inputStream.read(buffer, 3, 4);
                    System.out.println("===client==read333======"+readLen+"="+buffer[0]+"="+buffer[1]+"="+buffer[2]+"="+buffer[3]+"="+buffer[4]+"="+buffer[5]+"="+buffer[6]);
                }else{
                    Arrays.fill(buffer,(byte)0);
                    readLen=0;
                    readLen = inputStream.read(buffer, 0, 1);
//                    System.out.println("===client==read000======"+readLen+"="+buffer[0]);
                }


//                if(readLen >=0 && readLen < 7){
                if(readLen < 7){
//                    readLen += inputStream.read(buffer, readLen, 7-readLen);
                    len=0;
//                    System.out.println("===client==readLen======" + readLen +"="+buffer[0]+"="+buffer[1]+"="+buffer[2]+"="+buffer[3]+"="+buffer[4]+"="+buffer[5]+"="+buffer[6]);
                }else {

                    System.out.println("===client==header======" + readLen + "==" + buffer[0] + "=" + buffer[1] + "==" + buffer[2]);
                    if (((buffer[0] == 0x01) && (buffer[1] == 0x02) && (buffer[2] < 0x4F)) || ((buffer[0] == 0x01) && (buffer[1] == 0x01) && (buffer[2] == 0x02))) {

                        dataLen = byteToint(buffer, 3);
                        System.out.println("==Super==dataLen=" + dataLen + "==" + buffer[0] + "=" + buffer[1] + "==" + buffer[2]);
                        receiveData = new byte[dataLen + 9];
                        System.arraycopy(buffer, 0, receiveData, 0, 7);
                        needDataLen = dataLen + 2;
                        while (needDataLen > 0) {
                            readLen = inputStream.read(receiveData, dataLen + 2 + 7 - needDataLen, needDataLen);
//                            System.out.println("=needDataLen=" + needDataLen + "=readLen=" + readLen);
                            needDataLen = needDataLen - readLen;
                        }
                        dataLen = receiveData.length;

                        if (dataLen > 0) {
                            while (len < dataLen) {
                                System.out.println("===SocketREV===" + receiveData[len] + "====" + receiveData[len + 1] + "==" + receiveData[len + 2] + "==" + len + "=datalen=" + dataLen);
                                if ((receiveData[len] == 0x01) && (receiveData[len + 1] == 0x02) && (receiveData[len + 2] < 0x4f)) {
                                    tmp = byteToint(receiveData, len + 3);
                                    ReSuperData = new byte[7 + tmp + 2];
                                    System.arraycopy(receiveData, len, ReSuperData, 0, ReSuperData.length);
                                    System.out.println("=CRC=" + crc16(ReSuperData)[0] +"=="+crc16(ReSuperData)[1]+ "=readLen=" +ReSuperData[ReSuperData.length - 2]+"="+ReSuperData[ReSuperData.length - 1]);
                                    if (crc16(ReSuperData)[0] == ReSuperData[ReSuperData.length - 2] && crc16(ReSuperData)[1] == ReSuperData[ReSuperData.length - 1]) {

                                        reValue = new String(ReSuperData, 7, tmp);

                                        WritableMap eventParams = Arguments.createMap();
//                                        eventParams.putInt("client", cId);
                                        eventParams.putString("data", (reValue));
                                        eventParams.putInt("cmd", receiveData[len + 2]);
                                        sendEvent(mReactContext, event_data, eventParams);
                                        System.out.println("===SocketREV CMD:==" + reValue + "==len=" + len + "=datalen=" + dataLen + "==readLen=" + readLen);
                                        readLen = 0;
                                        break;
                                    } else {
                                        len++;
                                        System.out.println("==SocketREV CMD==CRC error=" + ReSuperData[ReSuperData.length - 2] + ReSuperData[ReSuperData.length - 1]);

                                        for (int a = 0; a < (dataLen - len); a++) {
                                            System.out.print(ReSuperData[a] + " ");
                                        }
                                        dataLen = 0;
                                        readLen = 0;
                                        needDataLen = 0;
                                        break;
                                    }
                                }else if((receiveData[len] == 0x01) && (receiveData[len + 1] == 0x01) && (receiveData[len + 2] == 0x02)){
                                    tmp = byteToint(receiveData, len + 3);
                                    ReSuperData = new byte[7 + tmp + 2];
                                    System.arraycopy(receiveData, len, ReSuperData, 0, ReSuperData.length);
                                    System.out.println("=login=CRC=" + crc16(ReSuperData)[0] +"=="+crc16(ReSuperData)[1]+ "=readLen=" +ReSuperData[ReSuperData.length - 2]+"="+ReSuperData[ReSuperData.length - 1]);
                                    if (crc16(ReSuperData)[0] == ReSuperData[ReSuperData.length - 2] && crc16(ReSuperData)[1] == ReSuperData[ReSuperData.length - 1]) {

                                        reValue = new String(ReSuperData, 7, tmp);

//                                        WritableMap eventParams = Arguments.createMap();
//                                        eventParams.putString("data", (reValue));
//                                        eventParams.putInt("cmd", receiveData[len + 2]);
//                                        sendEvent(mReactContext, event_data, eventParams);
                                        WritableMap eventParams = Arguments.createMap();
                                        sendEvent(mReactContext, event_connect, eventParams);
                                        System.out.println("===SocketREV=login=:==" + reValue + "==len=" + len + "=datalen=" + dataLen + "==readLen=" + readLen);

                                        try {
                                            JSONObject jsonObject = new JSONObject();
                                            jsonObject.put("Status", "Success");
                                            write(jsonObject.toString(), 2);
                                            break;
                                        }catch(Exception e){
                                            System.out.println("==JSONObject==err="+e.toString());
                                        }

                                        readLen = 0;
                                        break;
                                    } else {
                                        len++;
                                        System.out.println("==SocketREV=login==CRC error=" + ReSuperData[ReSuperData.length - 2] + ReSuperData[ReSuperData.length - 1]);

                                        for (int a = 0; a < (dataLen - len); a++) {
                                            System.out.print(ReSuperData[a] + " ");
                                        }
                                        dataLen = 0;
                                        readLen = 0;
                                        needDataLen = 0;
                                        break;
                                    }
                                } else {
                                    len++;
                                    if ((dataLen - len) < 9) {
                                        break;
                                    }
                                }
                            }
                        }
                    } else {
                        readLen=0;
                        System.out.println("===client==not==readLen=0====");
//                        len++;
//                        if ((dataLen - len) < 9) {
//                            break;
//                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.e(eTag, "watchIncoming", e);
            handleIOException(e);
        }
    }

    private void handleIOException(IOException e) {
        //debug log
        Log.e(eTag, "Client IOException", e);
        //emit event
        String message = e.getMessage();
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("error", message);
        isOpen = false;
        if (message.equals("Socket closed")) {
            isOpen = false;
            sendEvent(mReactContext, event_closed, eventParams);
        } else {
            sendEvent(mReactContext, event_error, eventParams);
        }
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (Exception e1) {
                Log.e(eTag, "Client Exception close", e1);
            }
        }
//        connectSocket();
    }

    private void handleUnknownHostException(UnknownHostException e) {
        //debug log
        Log.e(eTag, "Client UnknownHostException", e);
        //emit event
        String message = e.getMessage();
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("error", e.getMessage());
        sendEvent(mReactContext, event_error, eventParams);
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

    private int byteToint(byte b[], int offset) {

        return (b[offset + 3] & 0xFF) | ((b[offset + 2] & 0xFF) << 8) | ((b[offset + 1] & 0xFF) << 16) | ((b[offset]) << 24);
    }

}
