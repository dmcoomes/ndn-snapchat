package memphis.myapplication;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.squareup.picasso.Picasso;

import static com.google.zxing.integration.android.IntentIntegrator.QR_CODE_TYPES;

import net.named_data.jndn.ContentType;
import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.InterestFilter;
import net.named_data.jndn.MetaInfo;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnInterestCallback;
import net.named_data.jndn.OnRegisterFailed;
import net.named_data.jndn.OnRegisterSuccess;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.security.pib.AndroidSqlite3Pib;
import net.named_data.jndn.security.pib.Pib;
import net.named_data.jndn.security.pib.PibIdentity;
import net.named_data.jndn.security.pib.PibImpl;
import net.named_data.jndn.security.pib.PibKey;
import net.named_data.jndn.security.tpm.Tpm;
import net.named_data.jndn.security.tpm.TpmBackEnd;
import net.named_data.jndn.security.tpm.TpmBackEndFile;
import net.named_data.jndn.util.Blob;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import static java.lang.Thread.sleep;

import memphis.myapplication.tasks.FetchingTask;

public class MainActivity extends AppCompatActivity {

    // security v2 experimental changes
    // look at KeyChain.java; with V2, it works with CertificateV2, Pib, Tpm, and Validator
    public AndroidSqlite3Pib m_pib;
    public TpmBackEndFile m_tpm;
    public PibIdentity m_pibIdentity;

    //
    // not sure if globals instance is necessary here but this should ensure we have at least one instance so the vars exist
    Globals globals = (Globals) getApplication();
    public KeyChain keyChain;
    public Face face;
    public FaceProxy faceProxy;
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    /*private final int FILE_SELECT_REQUEST_CODE = 0;
    private final int FILE_QR_REQUEST_CODE = 1;
    private final int SCAN_QR_REQUEST_CODE = 2;*/
    private final int CAMERA_REQUEST_CODE = 0;
    // private final int VIEW_FILE = 4;

    private boolean netThreadShouldStop = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_main);
        setContentView(R.layout.boxes);
        setupToolbar();
        setupGrid();

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        // check if user has given us permissions for storage manipulation (one time dialog box)
        int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }
        boolean faceExists = (Globals.face == null);
        Log.d("onCreate", "Globals face is null?: " + faceExists +
                "; Globals security is setup: " + Globals.has_setup_security);
        // need to check if we have an existing face or if security is not setup; either way, we
        // need to make changes; see setup_security()
        if (faceExists || !Globals.has_setup_security) {
            setup_security();
        }

        face = Globals.face;
        faceProxy = Globals.faceProxy;
        keyChain = Globals.keyChain;

        startNetworkThread();
    }

    private void setupToolbar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.app_toolbar);
        FileManager manager = new FileManager(getApplicationContext());
        ImageView imageView = (ImageView) findViewById(R.id.toolbar_main_photo);
        File file = manager.getProfilePhoto();
        if(file == null || file.length() == 0) {
            Picasso.get().load(R.drawable.bandit).fit().centerCrop().into(imageView);
        }
        else {
            Picasso.get().load(file).fit().centerCrop().into(imageView);
        }
        setSupportActionBar(toolbar);
    }

    private void setupGrid() {
        TypedValue tv = new TypedValue();
        this.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true);
        int actionBarHeight = getResources().getDimensionPixelSize(tv.resourceId);

        GridView gridView = (GridView) findViewById(R.id.mainGrid);
        ImageAdapter imgAdapter = new ImageAdapter(this, actionBarHeight);
        Integer[] images = {R.drawable.florence, R.drawable.hotel, R.drawable.park, R.drawable.atlanta};
        String[] text = {"Camera", "Files", "Friends", "See Photos"};
        imgAdapter.setGridView(gridView);
        imgAdapter.setPhotoResources(images);
        imgAdapter.setTextValues(text);
        gridView.setAdapter(imgAdapter);

        gridView.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            public void onItemClick(AdapterView<?> parent,
                                    View v, int position, long id)
            {
                switch (position) {
                    case 0:
                        startUpCamera();
                        break;
                    case 1:
                        startFiles();
                        break;
                    case 2:
                        startMakingFriends();
                        break;
                    case 3:
                        seeRcvdPhotos();
                        break;
                    default:
                        Log.d("onGridImage", "selected image does not match a position in switch statment.");
                        break;
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        Log.d("menuInflation", "Inflated");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        Log.d("item", item.toString());
        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * This function sets up identity storage, keys, and the face our app will use.
     */
    public void setup_security() {
        FileManager manager = new FileManager(getApplicationContext());
        // /ndn-snapchat/<username>
        Name appAndUsername = new Name("/" + getString(R.string.app_name) + "/" + manager.getUsername());

        Context context = getApplicationContext();
        String rootPath = getApplicationContext().getFilesDir().toString();
        String pibPath = "pib-sqlite3:" + rootPath;

        face = new Face();
        try {
            m_pib = new AndroidSqlite3Pib(rootPath, "/pib.db");
            Globals.setPib(m_pib);
        }
        catch(PibImpl.Error e) {
            e.printStackTrace();
        }

        // jndn has a typo in its getter
        m_tpm = new TpmBackEndFile(TpmBackEndFile.getDefaultDirecoryPath(context.getFilesDir()));
        Globals.setTpmBackEndFile(m_tpm);
        try {
            m_pib.setTpmLocator("tpm-file:" + TpmBackEndFile.getDefaultDirecoryPath(context.getFilesDir()));
        }
        catch(PibImpl.Error e) {
            e.printStackTrace();
        }

        try {
            keyChain = new KeyChain(pibPath, m_pib.getTpmLocator());
        }
        catch(SecurityException | IOException | PibImpl.Error | KeyChain.Error e) {
            e.printStackTrace();
        }

        Name identity = new Name(appAndUsername);
        Name defaultCertificateName;
        PibIdentity pibId;
        PibKey key;

        try {
            // see if the identity exists; if it doesn't, this will throw an error
            pibId = keyChain.getPib().getIdentity(identity);
            key = pibId.getDefaultKey();
            keyChain.setDefaultIdentity(pibId);
            keyChain.setDefaultKey(pibId, key);
            keyChain.getPib().setDefaultIdentity_(identity);
            Globals.setPubKeyName(key.getName());
            Globals.setPublicKey(key.getPublicKey());
            Globals.setDefaultPibId(pibId);
        }
        catch(PibImpl.Error | Pib.Error e) {
            try {
                pibId = keyChain.createIdentityV2(identity);
                key = pibId.getDefaultKey();
                keyChain.setDefaultIdentity(pibId);
                keyChain.setDefaultKey(pibId, key);
                keyChain.getPib().setDefaultIdentity_(identity);
                Globals.setPubKeyName(key.getName());
                Globals.setPublicKey(key.getPublicKey());
                Globals.setDefaultPibId(pibId);
            }
            catch(PibImpl.Error | Pib.Error | TpmBackEnd.Error | Tpm.Error | KeyChain.Error ex) {
                ex.printStackTrace();
            }
        }

        Globals.setDefaultIdName(appAndUsername);

        try {
            defaultCertificateName = keyChain.getDefaultCertificateName();
        }
        catch(SecurityException e) {
            e.printStackTrace();
            defaultCertificateName = new Name("/bogus/certificate/name");
        }

        Globals.setKeyChain(keyChain);
        face.setCommandSigningInfo(keyChain, defaultCertificateName);
        Globals.setFace(face);
        Globals.setFaceProxy(new FaceProxy());
        Globals.setHasSecurity(true);
        Log.d("setup_security", "Security was setup successfully");

        try {
            // since everyone is a potential producer, register your prefix
            register_with_NFD(appAndUsername);
        } catch (IOException | PibImpl.Error e) {
            e.printStackTrace();
        }
    }

    // Eventually, we should move this to a Service, but for now, this thread consistently calls
    // face.processEvents() to check for any changes, such as publishing or fetching.
    private final Thread networkThread = new Thread(new Runnable() {
        @Override
        public void run() {
            boolean faceExists = Globals.face == null;
            Log.d("onCreate", "Globals face is null?: " + faceExists + "; Globals security is setup: " + Globals.has_setup_security);
            if (!Globals.has_setup_security) {
                setup_security();
            }
            try {
                sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            while (!netThreadShouldStop) {
                try {
                    face.processEvents();
                    sleep(100);
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // face.shutdown();
        }
    });

    private void startNetworkThread() {
        if (!networkThread.isAlive()) {
            netThreadShouldStop = false;
            networkThread.start();
        }
    }

    private void stopNetworkThread() {
        netThreadShouldStop = true;
    }

    protected boolean appThreadIsRunning() {
        return networkThread.isAlive();
    }

    /**
     * Android is very particular about UI processes running on a separate thread. This function
     * creates and returns a Runnable thread object that will display a Toast message.
     */
    public Runnable makeToast(final String s) {
        return new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show();
            }
        };
    }

    /*/**
     * Runs FetchingTask, which will use the SegmentFetcher to retrieve data using the provided Interest
     * @param interest the interest for the data we want
     */
    /*public void fetch_data(final Interest interest) {
        // interest.setInterestLifetimeMilliseconds(10000);
        // /tasks/FetchingTask
        new FetchingTask(m_mainActivity).execute(interest);
    }*/

    /**
     * Registers the provided name with NFD. This is intended to occur whenever the app starts up.
     * @param name The provided name should be /ndn-snapchat/<username>
     * @throws IOException
     * @throws PibImpl.Error
     */
    public void register_with_NFD(Name name) throws IOException, PibImpl.Error {

        if (!Globals.has_setup_security) {
            setup_security();
            while (!Globals.has_setup_security)
                try {
                    wait(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
        }
        try {
            Log.d("register_with_nfd", "Starting registration process.");
            face.registerPrefix(name,
                    onDataInterest,
                    new OnRegisterFailed() {
                        @Override
                        public void onRegisterFailed(Name prefix) {
                            Log.d("OnRegisterFailed", "Registration Failure");
                            String msg = "Registration failed for prefix: " + prefix.toUri();
                            runOnUiThread(makeToast(msg));
                        }
                    },
                    new OnRegisterSuccess() {
                        @Override
                        public void onRegisterSuccess(Name prefix, long registeredPrefixId) {
                            Log.d("OnRegisterSuccess", "Registration Success for prefix: " + prefix.toUri() + ", id: " + registeredPrefixId);
                            String msg = "Successfully registered prefix: " + prefix.toUri();
                            runOnUiThread(makeToast(msg));
                        }
                    });
        }
        catch (IOException | SecurityException e) {
            e.printStackTrace();
        }
    }

    /*/**
     * Starts a new thread to publish the file/photo data.
     * @param blob Blob of content
     * @param prefix Name of the file (currently absolute path)
     */
    /*public void publishData(final Blob blob, final Name prefix) {
        Thread publishingThread = new Thread(new Runnable() {
            public void run() {
                try {
                    ArrayList<Data> fileData = new ArrayList<>();
                    ArrayList<Data> packets = packetize(blob, prefix);
                    // it would be null if this file is already in our cache so we do not packetize
                    if(packets != null) {
                        Log.d("publishData", "Publishing with prefix: " + prefix);
                        for (Data data : packetize(blob, prefix)) {
                            keyChain.sign(data);
                            fileData.add(data);
                        }

                        faceProxy.putInCache(fileData);
                        FileManager manager = new FileManager(getApplicationContext());
                        String filename = prefix.toUri();
                        Bitmap bitmap = QRExchange.makeQRCode(filename);
                        manager.saveFileQR(bitmap, filename);
                    }
                    else {
                        Log.d("publishData", "No need to publish; " + prefix.toUri() + " already in cache.");
                    }
                } catch (PibImpl.Error | SecurityException | TpmBackEnd.Error |
                        KeyChain.Error e)

                {
                    e.printStackTrace();
                }
            }
        });
        publishingThread.start();
    }*/

    //public void select_files(View view) {
        // ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file
        // browser.
     //   Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        // To search for all documents available via installed storage providers,
        // it would be "*/*".
     //   intent.setType("*/*");
     //   startActivityForResult(intent, FILE_SELECT_REQUEST_CODE);
    //}

    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent resultData) {

        Log.d("onActivityResult", "requestCode: " + requestCode);
        Uri uri;
        if (resultData != null) {
            if (requestCode == CAMERA_REQUEST_CODE) {
                try {
                    Bitmap pic = (Bitmap) resultData.getExtras().get("data");
                }
                catch (NullPointerException e) {
                    // heads up; this happens when you exit the camera without taking a photo; don't
                    // worry about it when it pops up.
                    e.printStackTrace();
                }
            }
            else {
                Log.d("onActivityResult", "Unexpected activity requestcode caught");
            }
        }
    }

    // credit: https://stackoverflow.com/questions/13209494/how-to-get-the-full-file-path-from-uri/41520090

    /*/**
     * Converts a uri to its appropriate file pathname
     * @param uri file uri
     * @return
     */
    /*public String getFilePath(Uri uri) {
        String selection = null;
        String[] selectionArgs = null;
        if (DocumentsContract.isDocumentUri(getApplicationContext(), uri)) {
            if (uri.getAuthority().equals("com.android.externalstorage.documents")) {
                final String docId = DocumentsContract.getDocumentId(uri);
                Log.d("file selection", "docId: " + docId);
                final String[] split = docId.split(":");
                return Environment.getExternalStorageDirectory() + "/" + split[1];
            }
            else if (uri.getAuthority().equals("com.android.providers.downloads.documents")) {
                final String id = DocumentsContract.getDocumentId(uri);
                uri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
            }
            else if (uri.getAuthority().equals("com.android.providers.media.documents")) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];
                if ("image".equals(type)) {
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }
                selection = "_id=?";
                selectionArgs = new String[]{split[1]};
            }
        }

        if (uri.getScheme().equalsIgnoreCase("content")) {
            String[] projection = {MediaStore.Images.Media.DATA};
            Cursor cursor = null;
            try {
                cursor = getApplicationContext().getContentResolver().query(uri, projection, selection, selectionArgs, null);
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                if (cursor.moveToFirst()) {
                    return cursor.getString(column_index);
                }
            } catch (Exception e) {
                return null;
            }
        }
        else if (uri.getScheme().equalsIgnoreCase("file")) {
            return uri.getPath();
        }
        return null;
    }*/

    /*/**
     * Start a file selection activity to find a QR image to display. This is triggered by pressing
     * the "Display QR" button.
     * @param view The view of MainActivity passed by our button press.
     */
    /*public void lookup_file_QR(View view) {
        // ACTION_GET_CONTENT is used for reading; no modifications
        // We're going to find a png file of our choosing (should be used for displaying QR codes,
        // but it can display any image)
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        FileManager manager = new FileManager(getApplicationContext());
        File appDir = new File(manager.getFilesDir());
        Uri uri = Uri.fromFile(appDir);
        // start in app's file directory and limit allowable selections to .png files
        intent.setDataAndType(uri, "image/png");
        startActivityForResult(intent, FILE_QR_REQUEST_CODE);
    }*/

    /*/**
     * initiate scan for QR codes upon button press
     */
    /*public void scanFileQR(View view) {
        IntentIntegrator scanner = new IntentIntegrator(this);
        // only want QR code scanner
        scanner.setDesiredBarcodeFormats(QR_CODE_TYPES);
        scanner.setOrientationLocked(true);
        // back facing camera id
        scanner.setCameraId(0);
        Intent intent = scanner.createScanIntent();
        startActivityForResult(intent, SCAN_QR_REQUEST_CODE);
    }*/

    /*/**
     * This takes a Blob and divides it into NDN data packets
     * @param raw_blob The full content of data in Blob format
     * @param prefix
     * @return returns an ArrayList of all the data packets
     */
    /*public ArrayList<Data> packetize(Blob raw_blob, Name prefix) {
        if(!faceProxy.hasKey(prefix)) {
            final int VERSION_NUMBER = 0;
            final int DEFAULT_PACKET_SIZE = 8000;
            int PACKET_SIZE = (DEFAULT_PACKET_SIZE > raw_blob.size()) ? raw_blob.size() : DEFAULT_PACKET_SIZE;
            ArrayList<Data> datas = new ArrayList<>();
            int segment_number = 0;
            ByteBuffer byteBuffer = raw_blob.buf();
            do {
                // need to check for the size of the last segment; if lastSeg < PACKET_SIZE, then we
                // should not send an unnecessarily large packet. Also, if smaller, we need to prevent BufferUnderFlow error
                if (byteBuffer.remaining() < PACKET_SIZE) {
                    PACKET_SIZE = byteBuffer.remaining();
                }
                Log.d("packetize things", "PACKET_SIZE: " + PACKET_SIZE);
                byte[] segment_buffer = new byte[PACKET_SIZE];
                Data data = new Data();
                Name segment_name = new Name(prefix);
                segment_name.appendVersion(VERSION_NUMBER);
                segment_name.appendSegment(segment_number);
                data.setName(segment_name);
                try {
                    Log.d("packetize things", "full data name: " + data.getFullName().toString());
                } catch (EncodingException e) {
                    Log.d("packetize things", "unable to print full name");
                }
                try {
                    Log.d("packetize things", "byteBuffer position: " + byteBuffer.position());
                    byteBuffer.get(segment_buffer, 0, PACKET_SIZE);
                } catch (IndexOutOfBoundsException e) {
                    e.printStackTrace();
                }
                data.setContent(new Blob(segment_buffer));
                MetaInfo meta_info = new MetaInfo();
                meta_info.setType(ContentType.BLOB);
                // not sure what is a good freshness period
                meta_info.setFreshnessPeriod(30000);
                if (!byteBuffer.hasRemaining()) {
                    // Set the final component to have a final block id.
                    Name.Component finalBlockId = Name.Component.fromSegment(segment_number);
                    meta_info.setFinalBlockId(finalBlockId);
                }
                data.setMetaInfo(meta_info);
                datas.add(data);
                segment_number++;
            } while (byteBuffer.hasRemaining());
            return datas;
        }
        else {
            return null;
        }
    }*/

    // start activity for add friends
    public void startMakingFriends() {
        Intent intent = new Intent(this, AddFriendActivity.class);
        startActivity(intent);
    }

    /*// browse your rcv'd files; start in rcv'd files dir; for right now, we will have a typical
    // file explorer and opener. This is intended for testing.
    public void browseRcvdFiles(View view) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        FileManager manager = new FileManager(getApplicationContext());
        File rcvFilesDir = new File(manager.getRcvdFilesDir());
        Uri uri = Uri.fromFile(rcvFilesDir);
        Log.d("browse", uri.toString());*/
        // start in app's file directory and limit allowable selections to .png files
        //intent.setDataAndType(uri, "*/*");
        //startActivityForResult(intent, VIEW_FILE);
    //}

    public void seeRcvdPhotos() {
        Intent intent = new Intent(this, NewContentActivity.class);
        startActivity(intent);
    }

    /**
     * Triggered by button press. This acts as a helper function to first ask for permission to
     * access the camera if we do not have it. If we are granted permission or have permission, we
     * will call startCamera()
     */
    // public void startCamera(View view) {
    public void startUpCamera() {
        int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if(permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
        }
        else {
            startCamera();
        }
    }

    /**
     * Opens the camera so we can capture an image or video. See onActivityResult for how media
     * is handled.
     */
    public void startCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // name the photo by using current time
        String tsPhoto = (new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new Date())) + ".jpg";
        /* The steps below are necessary for photo captures. We set up a temporary file for our
           photo and pass the information to the Camera Activity. This is where it will store the
           photo if we choose to save it. */
        FileManager manager = new FileManager(getApplicationContext());
        File pic = new File(manager.getPhotosDir(), tsPhoto);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(pic));
        startActivityForResult(intent, CAMERA_REQUEST_CODE);
    }

    /**
     * This checks if the user gave us permission for the camera or not when the dialog box popped up.
     * @param requestCode
     * @param permissions
     * @param grantResults
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            }
            else {
                runOnUiThread(makeToast("Can't access camera without your permission."));
            }
        }
    }

    public void startFiles() {
        Intent intent = new Intent(this, FilesActivity.class);
        startActivity(intent);
    }

    /**
     * This is registered with our prefix. Any interest sent with prefix /ndn-snapchat/<username>
     * will be caught by this callback. We send it to the faceProxy to deal with it.
     */
    private final OnInterestCallback onDataInterest = new OnInterestCallback() {
        @Override
        public void onInterest(Name prefix, Interest interest, Face face, long interestFilterId,
                               InterestFilter filterData) {
            Log.d("OnInterestCallback", "Called OnInterestCallback with Interest: " + interest.getName().toUri());
            faceProxy.process(interest);
        }
    };

    // maybe we need our own onData callback since it is used in expressInterest (which is called by the SegmentFetcher)
}
