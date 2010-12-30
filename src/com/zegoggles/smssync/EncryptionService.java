package com.zegoggles.smssync;

import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.thialfihar.android.apg.IApgService;


public class EncryptionService {

    private String TAG = "SmsBackup+";

    /** Remote service for decrypting and encrypting data */
    public IApgService apgService = null;

    /** Set apgService accordingly to connection status */
    private ServiceConnection apgConnection = new ServiceConnection() {
	    public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d( TAG, "IApgService bound to apgService" );
		    apgService = IApgService.Stub.asInterface(service);
	    }
	    public void onServiceDisconnected(ComponentName className) {
            Log.d( TAG, "IApgService disconnected" );
		    apgService = null;
	    }
    };

    private final Context mContext;

    public EncryptionService( Context ctx ) {
        Log.d( TAG, "EncryptionService created" );
        mContext = ctx;
    }

    /** try to connect to the apg service */
    private boolean connect() {
        Log.d( TAG, "trying to bind the apgService to context" );

        if( apgService != null ) {
            Log.d( TAG, "allready connected" );
            return true;
        }

        try {
            mContext.bindService(new Intent(IApgService.class.getName()), apgConnection, mContext.BIND_AUTO_CREATE); 
        } catch( Exception e ) {
            Log.d( TAG, "could not bind APG service" );
            return false;
        }

        int wait_count = 0;
        while ( apgService == null && wait_count++ < 15 ) {
            Log.d( TAG, "sleeping 1 second to wait for apg" );
            android.os.SystemClock.sleep(1000);
        };

        if( wait_count >= 15 ) {
            Log.d( TAG, "slept waiting for nothing!" );
            return false;
        }

        return true;
    }
    
    private boolean initialize() {
        if( apgService == null ) {
            if( !connect() ) {
                Log.d( TAG, "connection to apg service failed" );
                return false;
            }
        }
        return true;
    }


    public void disconnect() {
        Log.d( TAG, "disconnecting apgService" );
        if( apgService != null ) {
            mContext.unbindService( apgConnection );
            apgService = null;
        }
    }

    /** encrypt a string with a passphrase */
    public String encrypt( String msg, String pass ) {
        Log.d( TAG, "encrypting string" );
        if( !initialize() )
            return null;

        String encrypted_msg = null;
        try {
           encrypted_msg = apgService.encrypt_with_passphrase( msg, pass );
        } catch( android.os.RemoteException e ) {
            Log.d( TAG, "Error on encrypting body" );
            return null;
        }

        Log.d( TAG, "encrypting done" );
        return encrypted_msg;
    }

    public String decrypt( String msg, String pass ) {
        Log.d( TAG, "decrypting string" );
        if( !initialize() )
            return null;

        String decrypted_msg = null;
        try {
           decrypted_msg = apgService.decrypt_with_passphrase( msg, pass );
        } catch( android.os.RemoteException e ) {
            Log.d( TAG, "Error on decrypting body" );
            return null;
        }

        Log.d( TAG, "decrypting done" );
        return decrypted_msg;

    }
}
