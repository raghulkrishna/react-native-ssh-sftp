package me.dylankenneally.rnssh;

import android.os.Environment;
import android.util.Log;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import okhttp3.internal.Util;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

import com.jcraft.jsch.KeyPair;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ByteArrayOutputStream;

public class RNSshClientModule extends ReactContextBaseJavaModule {
  private class SSHClient {
    Session _session;
    String _key;
    BufferedReader _bufferedReader;
    DataOutputStream _dataOutputStream;
    Channel _channel = null;
    ChannelSftp _sftpSession = null;
    Boolean _downloadContinue = false;
    Boolean _uploadContinue = false;
  }

  private final ReactApplicationContext reactContext;
  private static final String LOGTAG = "RNSSHClient";
  private static final String DOWNLOAD_PATH = Environment.getExternalStorageDirectory().getPath();

  Map<String, SSHClient> clientPool = new HashMap<>();

  public RNSshClientModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  @Override
  public String getName() {
    return "RNSSHClient";
  }

  private void sendEvent(ReactContext reactContext,
                         String eventName,
                         @Nullable WritableMap params) {
    reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
  }

  @ReactMethod
  private void connectToHostByPassword(final String host, final Integer port, final String username, final String passwordOrKey, final String key, final Callback callback) {
    connectToHost(host, port, username, passwordOrKey, null, key, callback);
  }

  @ReactMethod
  private void connectToHostByKey(final String host, final Integer port, final String username, final ReadableMap passwordOrKey, final String key, final Callback callback) {
    connectToHost(host, port, username, null, passwordOrKey, key, callback);
  }
  private int getKeyTypeFromString(String type) throws IllegalArgumentException {
    if (type == null) {
        throw new IllegalArgumentException("Key type cannot be null");
    }
    switch (type.toLowerCase()) {
        case "dsa":
            return KeyPair.DSA;
        case "rsa":
            return KeyPair.RSA;
        case "ecdsa":
            return KeyPair.ECDSA;
        case "ed25519":
            return KeyPair.ED25519;
        case "ed448":
            return KeyPair.ED448;
        default:
            throw new IllegalArgumentException("Unsupported key type: " + type);
    }
}

  @ReactMethod
  public void generateKeyPair(final String type, @Nullable final String passphrase, final int keySize, final String comment, final Callback callback) {
    new Thread(new Runnable() {
        public void run() {
            try {
                int keyType = getKeyTypeFromString(type); // You'll implement this to translate string to type
                JSch jsch = new JSch();
                KeyPair kpair = KeyPair.genKeyPair(jsch, keyType, keySize);
                
                // callback.invoke("Finger print: " + kpair.getFingerPrint());
                ByteArrayOutputStream privateKeyOut = new ByteArrayOutputStream();
                ByteArrayOutputStream publicKeyOut = new ByteArrayOutputStream();
                kpair.writePrivateKey(privateKeyOut, passphrase.isEmpty() ? null : passphrase.getBytes());
                kpair.writePublicKey(publicKeyOut, comment);
                String privateKeyString = privateKeyOut.toString("UTF-8");
                String publicKeyString = publicKeyOut.toString("UTF-8");
                WritableMap keyMap = Arguments.createMap();
                keyMap.putString("privateKey", privateKeyString);
                keyMap.putString("publicKey", publicKeyString);
                callback.invoke(null, keyMap);

                privateKeyOut.close();
                publicKeyOut.close();
                kpair.dispose();
            } catch (Exception e) {
                Log.e(LOGTAG, "Failed to generate key pair", e);
                callback.invoke("Failed to generate key pair: " + e.toString());
            }
        }
    }).start();
}

  @ReactMethod
  public void getKeyDetails(String privateKey, Promise promise) {
  File tempPrivateKeyFile = null;
  try {
    // Create temporary files for the private and public keys
    tempPrivateKeyFile = File.createTempFile("temp_private_key", ".pem");
    tempPrivateKeyFile.deleteOnExit();

    try (FileWriter privateKeyWriter = new FileWriter(tempPrivateKeyFile);) {
      privateKeyWriter.write(privateKey);
    }

    JSch jsch = new JSch();
    KeyPair kpair = KeyPair.load(jsch, tempPrivateKeyFile.getAbsolutePath());

    String keyType;
    switch (kpair.getKeyType()) {
      case KeyPair.RSA:
        keyType = "RSA";
        break;
      case KeyPair.DSA:
        keyType = "DSA";
        break;
      case KeyPair.ECDSA:
        keyType = "ECDSA";
        break;
      case KeyPair.ED25519:
        keyType = "ED25519";
        break;
      default:
        keyType = "UNKNOWN";
    }
    int keySize = kpair.getKeySize();

    kpair.dispose();

    WritableMap result = Arguments.createMap();
    result.putString("keyType", keyType);
    result.putInt("keySize", keySize);
    promise.resolve(result);
  } catch (Exception e) {
    promise.reject("Error", e.getMessage());
  } finally {
    if (tempPrivateKeyFile != null) {
      tempPrivateKeyFile.delete();
    }
  }
}


  private void connectToHost(final String host, final Integer port, final String username,final String password, final ReadableMap keyPairs, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          JSch jsch = new JSch();

          if (password == null) {
            byte[] privateKey = keyPairs.getString("privateKey").getBytes();
            byte[] publicKey = keyPairs.hasKey("publicKey") ? keyPairs.getString("publicKey").getBytes() : null;
            byte[] passphrase = keyPairs.hasKey("passphrase") ? keyPairs.getString("passphrase").getBytes() : null;
            jsch.addIdentity("default", privateKey, publicKey, passphrase);
          }

          Session session = jsch.getSession(username, host, port);

          if (password != null)
            session.setPassword(password);

          Properties properties = new Properties();
          properties.setProperty("StrictHostKeyChecking", "no");
          session.setConfig(properties);
          session.connect();

          if (session.isConnected()) {
            SSHClient client = new SSHClient();
            client._session = session;
            client._key = key;
            clientPool.put(key, client);

            Log.d(LOGTAG, "Session connected");
            callback.invoke();
          }
        } catch (JSchException error) {
          Log.e(LOGTAG, "Connection failed: " + error.getMessage());
          callback.invoke(error.getMessage());
        } catch (Exception error) {
          Log.e(LOGTAG, "Connection failed: " + error.getMessage());
          callback.invoke(error.getMessage());
        }
      }
    }).start();
  }


  @ReactMethod
  public void execute(final String command, final String key, final Callback callback) {
    new Thread(new Runnable() {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          Session session = client._session;

          ChannelExec channel = (ChannelExec) session.openChannel("exec");
          channel.setCommand(command);
          channel.connect();

          String line, response = "";
          InputStream in = channel.getInputStream();
          BufferedReader reader = new BufferedReader(new InputStreamReader(in));
          while ((line = reader.readLine()) != null) {
            response += line + "\r\n";
          }

          callback.invoke(null, response);
        } catch (JSchException error) {
          Log.e(LOGTAG, "Error executing command: " + error.getMessage());
          callback.invoke(error.getMessage());
        } catch (Exception error) {
          Log.e(LOGTAG, "Error executing command: " + error.getMessage());
          callback.invoke(error.getMessage());
        }
      }
    }).start();
  }

  @ReactMethod
  public void startShell(final String key, final String ptyType, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          Session session = client._session;

          Channel channel = session.openChannel("shell");
          ((ChannelShell)channel).setPtyType(ptyType);
          channel.connect();

          InputStream in = channel.getInputStream();
          client._channel = channel;
          client._bufferedReader = new BufferedReader(new InputStreamReader(in));
          client._dataOutputStream = new DataOutputStream(channel.getOutputStream());

          callback.invoke();

//        int charVal;
          String line;
          while (client._bufferedReader != null && (line = client._bufferedReader.readLine()) != null) {
            WritableMap map = Arguments.createMap();
            map.putString("name", "Shell");
            map.putString("key", key);
            map.putString("value", line + '\n');
//          map.putString("value", String.valueOf(charVal));
            sendEvent(reactContext, "Shell", map);
          }

        } catch (JSchException error) {
          Log.e(LOGTAG, "Error starting shell: " + error.getMessage());
          callback.invoke(error.getMessage());
        } catch (IOException error) {
          Log.e(LOGTAG, "Error starting shell: " + error.getMessage());
          callback.invoke(error.getMessage());
        } catch (Exception error) {
          Log.e(LOGTAG, "Error sarting shell: " + error.getMessage());
          callback.invoke(error.getMessage());
        }
      }
    }).start();
  }

  @ReactMethod
  public void writeToShell(final String str, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          client._dataOutputStream.writeBytes(str);
          client._dataOutputStream.flush();
          callback.invoke();
        } catch (IOException error) {
          Log.e(LOGTAG, "Error writing to shell:" + error.getMessage());
          callback.invoke(error.getMessage());
        } catch (Exception error) {
          Log.e(LOGTAG, "Error writing to shell:" + error.getMessage());
          callback.invoke(error.getMessage());
        }
      }
    }).start();
  }

  @ReactMethod
  public void closeShell(final String key) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          if (client._channel != null) {
              client._channel.disconnect();
          }

          if (client._dataOutputStream != null) {
              client._dataOutputStream.flush();
              client._dataOutputStream.close();
          }

          if (client._bufferedReader != null) {
              client._bufferedReader.close();
          }
        } catch (IOException error) {
          Log.e(LOGTAG, "Error closing shell:" + error.getMessage());
        } catch (Exception error) {
          Log.e(LOGTAG, "Error closing shell:" + error.getMessage());
        }
      }
    }).start();
  }

  @ReactMethod
  public void connectSFTP(final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          ChannelSftp channelSftp = (ChannelSftp) client._session.openChannel("sftp");
          channelSftp.connect();
          client._sftpSession = channelSftp;
          callback.invoke();
        } catch (JSchException error) {
          Log.e(LOGTAG, "Error connecting SFTP:" + error.getMessage());
          callback.invoke(error.getMessage());
        } catch (Exception error) {
          Log.e(LOGTAG, "Error connecting SFTP:" + error.getMessage());
          callback.invoke(error.getMessage());
        }
      }
    }).start();
  }

  @ReactMethod
  public void disconnectSFTP(final String key) {
    new Thread(new Runnable()  {
      public void run() {
        SSHClient client = clientPool.get(key);
        if (client == null) {
            return;
        }
        if (client._sftpSession != null) {
          client._sftpSession.disconnect();
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpLs(final String path, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
        if (client == null) {
            throw new Exception("client is null");
        }
          ChannelSftp channelSftp = client._sftpSession;

          Vector<LsEntry> files = channelSftp.ls(path);
          WritableArray response = new WritableNativeArray();

          for (LsEntry file: files) {
            int isDir = 0;
            String filename = file.getFilename();
            if (filename.trim().equals(".") || filename.trim().equals(".."))
              continue;

            if (file.getAttrs().isDir()) {
              isDir = 1;
              filename += '/';
            }
            String str = String.format(Locale.getDefault(),
              "{\"filename\":\"%s\"," +
              "\"isDirectory\":%d," +
              "\"modificationDate\":\"%s\"," +
              "\"lastAccess\":\"%s\"," +
              "\"fileSize\":%d," +
              "\"ownerUserID\":%d," +
              "\"ownerGroupID\":%d," +
              "\"permissions\":\"%s\"," +
              "\"flags\":%d}",
              filename,
              isDir,
              file.getAttrs().getMTime(),
              file.getAttrs().getATime(),
              file.getAttrs().getSize(),
              file.getAttrs().getUId(),
              file.getAttrs().getGId(),
              file.getAttrs().getPermissions(),
              file.getAttrs().getFlags()
            );
            response.pushString(str);
          }
          callback.invoke(null, response);
        } catch (SftpException error) {
          Log.e(LOGTAG, "Failed to list path " + path);
          callback.invoke("Failed to list path " + path);
        } catch (Exception error) {
          Log.e(LOGTAG, "Failed to list path " + path);
          callback.invoke("Failed to list path " + path);
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpRename(final String oldPath, final String newPath, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          ChannelSftp channelSftp = client._sftpSession;
          channelSftp.rename(oldPath, newPath);
          callback.invoke();
        } catch (SftpException error) {
          Log.e(LOGTAG, "Failed to rename path " + oldPath);
          callback.invoke("Failed to rename path " + oldPath);
        } catch (Exception error) {
          Log.e(LOGTAG, "Failed to rename path " + oldPath);
          callback.invoke("Failed to rename path " + oldPath);
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpMkdir(final String path, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          ChannelSftp channelSftp = client._sftpSession;
          channelSftp.mkdir(path);
          callback.invoke();
        } catch (SftpException error) {
          Log.e(LOGTAG, "Failed to create directory " + path);
          callback.invoke("Failed to create directory " + path);
        } catch (Exception error) {
          Log.e(LOGTAG, "Failed to create directory " + path);
          callback.invoke("Failed to create directory " + path);
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpRm(final String path, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          ChannelSftp channelSftp = client._sftpSession;
          channelSftp.rm(path);
          callback.invoke();
        } catch (SftpException error) {
          Log.e(LOGTAG, "Failed to remove " + path);
          callback.invoke("Failed to remove " + path);
        } catch (Exception error) {
          Log.e(LOGTAG, "Failed to remove " + path);
          callback.invoke("Failed to remove " + path);
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpRmdir(final String path, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          ChannelSftp channelSftp = client._sftpSession;
          channelSftp.rmdir(path);
          callback.invoke();
        } catch (SftpException error) {
          Log.e(LOGTAG, "Failed to remove " + path);
          callback.invoke("Failed to remove " + path);
        } catch (Exception error) {
          Log.e(LOGTAG, "Failed to remove " + path);
          callback.invoke("Failed to remove " + path);
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpChmod(final String path, final int permissions, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          ChannelSftp channelSftp = client._sftpSession;
          channelSftp.chmod(permissions, path);
          callback.invoke();
        } catch (SftpException error) {
          final String msg = "Failed to chmod " + path + " with permissions " + permissions;
          Log.e(LOGTAG, msg);
          callback.invoke(msg);
        } catch (Exception error) {
          final String msg = "Failed to chmod " + path + " with permissions " + permissions;
          Log.e(LOGTAG, msg);
          callback.invoke(msg);
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpDownload(final String filePath, final String path, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          client._downloadContinue = true;
          ChannelSftp channelSftp = client._sftpSession;
          channelSftp.get(filePath, path, new progressMonitor(key, "DownloadProgress"));
          callback.invoke(null, path + '/' + (new File(filePath)).getName());
        } catch (SftpException error) {
          Log.e(LOGTAG, "Failed to download " + filePath);
          callback.invoke("Failed to download " + filePath);
        } catch (Exception error) {
          Log.e(LOGTAG, "Failed to download " + filePath);
          callback.invoke("Failed to download " + filePath);
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpUpload(final String filePath, final String path, final String key, final Callback callback) {
    new Thread(new Runnable()  {
      public void run() {
        try {
          SSHClient client = clientPool.get(key);
          if (client == null) {
              throw new Exception("client is null");
          }
          client._uploadContinue = true;
          ChannelSftp channelSftp = client._sftpSession;
          channelSftp.put(filePath, path + '/' + (new File(filePath)).getName(), new progressMonitor(key, "UploadProgress"), ChannelSftp.OVERWRITE);
          callback.invoke();
        } catch (SftpException error) {
          Log.e(LOGTAG, "Failed to upload " + filePath);
          callback.invoke("Failed to upload " + filePath);
        } catch (Exception error) {
          Log.e(LOGTAG, "Failed to upload " + filePath);
          callback.invoke("Failed to upload " + filePath);
        }
      }
    }).start();
  }

  @ReactMethod
  public void sftpCancelDownload(final String key) {
    SSHClient client = clientPool.get(key);
    if (client != null) {
        client._downloadContinue = false;
    }
  }

  @ReactMethod
  public void sftpCancelUpload(final String key) {
    SSHClient client = clientPool.get(key);
    if (client != null) {
        client._uploadContinue = false;
    }
  }

  @ReactMethod
  public void disconnect(final String key) {
    this.closeShell(key);
    this.disconnectSFTP(key);

    SSHClient client = clientPool.get(key);
    if (client != null) {
        client._session.disconnect();
    }
  }

  private class progressMonitor implements SftpProgressMonitor {
    private long max = 0;
    private long count = 0;
    private long downloadedPerc = 0;
    private String key;
    private String name;

    public progressMonitor(String key, String name) {
      this.key = key;
      this.name = name;
    }

    public void init(int arg0, String arg1, String arg2, long arg3) {
        this.max = arg3;
    }

    public boolean count(long arg0) {
      SSHClient client = clientPool.get(this.key);
      this.count += arg0;
      long newPerc = this.count * 100 / max;
      if(newPerc % 5 == 0 && newPerc > this.downloadedPerc) {
        this.downloadedPerc = newPerc;
        WritableMap map = Arguments.createMap();
        map.putString("name", this.name);
        map.putString("key", this.key);
        map.putString("value", Long.toString(this.downloadedPerc));
        sendEvent(reactContext, this.name, map);
      }
      boolean con;
      if (this.name.equals("DownloadProgress")) {
        con = client._downloadContinue;
      } else {
        con = client._uploadContinue;
      }
      return con;
    }

    public void end() {
    }
  }

  @ReactMethod
  public void addListener(String eventName) {
    // Keep: Required for RN built in Event Emitter Calls.
  }

  @ReactMethod
  public void removeListeners(Integer count) {
    // Keep: Required for RN built in Event Emitter Calls.
  }
}
