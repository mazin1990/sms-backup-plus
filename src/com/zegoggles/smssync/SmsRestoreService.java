package com.zegoggles.smssync;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.provider.CallLog;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.widget.EditText;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.BinaryTempFileBody;
import com.zegoggles.smssync.CursorToMessage.DataType;
import org.thialfihar.android.apg.utils.ApgCon;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.zegoggles.smssync.App.LOCAL_LOGV;
import static com.zegoggles.smssync.App.TAG;
import static com.zegoggles.smssync.ServiceBase.SmsSyncState.*;

public class SmsRestoreService extends ServiceBase {
    static int currentRestoredItems, itemsToRestoreCount,
               restoredCount, duplicateCount;

    static boolean isRunning, canceled = false;

    @Override public void onCreate() {
       asyncClearCache();
       BinaryTempFileBody.setTempDirectory(getCacheDir());
    }

    @Override protected void handleIntent(final Intent intent) {
        synchronized (ServiceBase.class) {
            if (!isRunning) {
                new RestoreTask().execute(PrefStore.getMaxItemsPerRestore(this));
            }
        }
    }

    class RestoreTask extends AsyncTask<Integer, SmsSyncState, Integer> {
        private final Context context = SmsRestoreService.this;

        private Set<String> smsIds     = new HashSet<String>();
        private Set<String> callLogIds = new HashSet<String>();
        private Set<String> uids       = new HashSet<String>();

        private BackupImapStore.BackupFolder smsFolder, callFolder;
        private CursorToMessage converter = new CursorToMessage(context, PrefStore.getUserEmail(context));
        private int max;

        // decryption related
        private boolean wait = false;
        private final Map<String,String> pgpKeyPassphrases = new HashMap<String,String>();
        private final Set<String>        pgpKeysToSkip = new HashSet<String>();
        private ApgCon mEnc;

        protected java.lang.Integer doInBackground(Integer... params) {
            this.max = params.length > 0 ? params[0] : -1;
            final boolean starredOnly    = PrefStore.isRestoreStarredOnly(context);
            final boolean restoreCallLog = PrefStore.isRestoreCallLog(context);
            final boolean restoreSms     = PrefStore.isRestoreSms(context);

            if (!restoreSms && !restoreCallLog) return null;

            try {
                acquireLocks(false);
                isRunning = true;

                publishProgress(LOGIN);
                smsFolder = getSMSBackupFolder();
                if (restoreCallLog) callFolder = getCallLogBackupFolder();

                publishProgress(CALC);

                final List<Message> msgs = new ArrayList<Message>();

                if (restoreSms) msgs.addAll(smsFolder.getMessages(max, starredOnly, null));
                if (restoreCallLog) msgs.addAll(callFolder.getMessages(max, starredOnly, null));

                itemsToRestoreCount = max <= 0 ? msgs.size() : Math.min(msgs.size(), max);

                long lastPublished = System.currentTimeMillis();
                for (int i = 0; i < itemsToRestoreCount && !canceled; i++) {

                    importMessage(msgs.get(i));
                    currentRestoredItems = i;

                    msgs.set(i, null); // help gc

                    if (System.currentTimeMillis() - lastPublished > 1000) {
                        publishProgress(RESTORE); // don't publish too often or we get ANRs
                        lastPublished = System.currentTimeMillis();
                    }

                    //clear cache periodically otherwise SD card fills up
                    if (i % 50 == 0) clearCache();

                    while( wait && !canceled) { // wait with next sms if someone requested
                        SystemClock.sleep(1000);
                    }
                }
                publishProgress(UPDATING_THREADS);
                updateAllThreads(false);

                return smsIds.size() + callLogIds.size();
            } catch (ConnectivityErrorException e) {
                lastError = translateException(e);
                publishProgress(CONNECTIVITY_ERROR);
                return null;
            } catch (AuthenticationFailedException e) {
                publishProgress(AUTH_FAILED);
                return null;
            } catch (MessagingException e) {
                Log.e(TAG, "error", e);
                lastError = translateException(e);
                publishProgress(GENERAL_ERROR);
                return null;
            } catch (IllegalStateException e) {
                // usually memory problems (Couldn't init cursor window)
                lastError = translateException(e);
                publishProgress(GENERAL_ERROR);
                return null;
            } finally {
                releaseLocks();
           }
        }

        @Override
        protected void onPostExecute(Integer result) {
            if (canceled) {
                Log.d(TAG, "restore canceled by user");
                publishProgress(CANCELED_RESTORE);
            } else if (result != null) {
                Log.d(TAG, "finished (" + result + "/" + uids.size() + ")");
                restoredCount = result;
                duplicateCount = uids.size() - result;
                publishProgress(FINISHED_RESTORE);
            }
            canceled = isRunning = false;
        }

        @Override protected void onProgressUpdate(SmsSyncState... progress) {
          if (progress != null && progress.length > 0) {
            if (smsSync != null) smsSync.statusPref.stateChanged(progress[0]);
            sState = progress[0];
          }
        }

        private void updateAllThreads(final boolean async) {
            // thread dates + states might be wrong, we need to force a full update
            // unfortunately there's no direct way to do that in the SDK, but passing a
            // negative conversation id to delete should to the trick

            // execute in background, might take some time
            final Thread t = new Thread() {
                @Override public void run() {
                    Log.d(TAG, "updating threads");
                    getContentResolver().delete(Uri.parse("content://sms/conversations/-1"), null, null);
                    Log.d(TAG, "finished");
                }
            };
            t.start();
            try {
              if (!async) t.join();
            } catch (InterruptedException ignored) { }
        }

        private void importMessage(Message message) {
            uids.add(message.getUid());

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.BODY);

            try {
                if (LOCAL_LOGV) Log.v(TAG, "fetching message uid " + message.getUid());

                message.getFolder().fetch(new Message[] { message }, fp, null);
                final DataType dataType = converter.getDataType(message);
                //only restore sms+call log for now
                switch (dataType) {
                    case CALLLOG: importCallLog(message); break;
                    case SMS:     importSms(message); break;
                    default: if (LOCAL_LOGV) Log.d(TAG, "ignoring restore of type: " + dataType);
                }
            } catch (MessagingException e) {
                Log.e(TAG, "error", e);
            } catch (IllegalArgumentException e) {
                // http://code.google.com/p/android/issues/detail?id=2916
                Log.e(TAG, "error", e);
            } catch (java.io.IOException e) {
                Log.e(TAG, "error", e);
            }
        }

        private void importSms(final Message message) throws IOException, MessagingException {
            if (LOCAL_LOGV) Log.v(TAG, "importSms("+message+")");
            final ContentValues values = converter.messageToContentValues(message);
            final Integer type = values.getAsInteger(SmsConsts.TYPE);

            // only restore inbox messages and sent messages - otherwise sms might get sent on restore
            if (type != null && (type == SmsConsts.MESSAGE_TYPE_INBOX ||
                                 type == SmsConsts.MESSAGE_TYPE_SENT) &&
                                 !smsExists(values) &&
                                 decrypt(values, false)) {

                final Uri uri = getContentResolver().insert(SMS_PROVIDER, values);
                if (uri != null) {
                    smsIds.add(uri.getLastPathSegment());
                    Long timestamp = values.getAsLong(SmsConsts.DATE);

                  if (timestamp != null &&
                      PrefStore.getMaxSyncedDateSms(context) < timestamp) {
                      updateMaxSyncedDateSms(timestamp);
                  }
                  if (LOCAL_LOGV) Log.v(TAG, "inserted " + uri);
                }
            } else {
                if (LOCAL_LOGV) Log.d(TAG, "ignoring sms");
            }
        }

        private void importCallLog(final Message message) throws MessagingException, IOException {
            if (LOCAL_LOGV) Log.v(TAG, "importCallLog("+message+")");
            final ContentValues values = converter.messageToContentValues(message);
            if (!callLogExists(values)) {
              final Uri uri = getContentResolver().insert(CALLLOG_PROVIDER, values);
              if (uri != null) callLogIds.add(uri.getLastPathSegment());
            } else {
              if (LOCAL_LOGV) Log.d(TAG, "ignoring call log");
            }
        }

        // returns true if successfully decrypted or nor decryption needed, false otherwise
        private boolean decrypt(ContentValues values, final boolean lastKeyWasWrong) throws IOException, MessagingException {
            final String pgpKey = values.getAsString(SmsConsts.PGP);
            if (pgpKey == null) return true; // no decryption required

            if (LOCAL_LOGV) Log.v(TAG, "pgp header is: " + pgpKey);
            if (pgpKeysToSkip.contains(pgpKey)) {
               Log.d(TAG, "Encrypted body, but user skipped that key before, so skip here, too");
               return false;
            }

            //if we get a encrypted msg, initialize our encodingService
            if (mEnc == null) mEnc = new ApgCon(getApplicationContext());

            mEnc.reset();
            // decrypt encrypted body before restoring
            mEnc.set_arg("MESSAGE", values.getAsString(SmsConsts.BODY));
            if (!pgpKeyPassphrases.containsKey(pgpKey)) {
                if (LOCAL_LOGV) Log.v(TAG, "Will ask for passphrase for key " + pgpKey);
                final AtomicBoolean waitForPgpPassphrase = new AtomicBoolean(true);
                smsSync.runOnUiThread(new Runnable() {
                    public void run() {
                        AlertDialog diag = getAskForPgpPassphraseDialog(pgpKey, lastKeyWasWrong).create();
                        diag.setOnDismissListener(new DialogInterface.OnDismissListener() {
                            public void onDismiss(DialogInterface dialog) {
                                waitForPgpPassphrase.set(false);
                            }
                        });
                        diag.show();
                    }
                });

                while (waitForPgpPassphrase.get() && !canceled) {
                    SystemClock.sleep(1000);
                }
            }

            if (canceled || pgpKeysToSkip.contains(pgpKey)) {
                if (LOCAL_LOGV) Log.v(TAG, "User wants to skip this key");
                return false;
            }

            mEnc.set_arg("PRIVATE_KEY_PASSPHRASE", pgpKeyPassphrases.get(pgpKey));

            boolean success = mEnc.call("decrypt");
            if (LOCAL_LOGV) {
                while (mEnc.has_next_warning()) Log.w(TAG, "Warning: " + mEnc.get_next_warning());
            }

            if (!success) {
                Log.e(TAG, "decryption returned error: ");
                while (mEnc.has_next_error()) Log.e(TAG, mEnc.get_next_error());

                pgpKeyPassphrases.remove(pgpKey);

                switch (mEnc.get_error()) {
                    // bad or missing passphrase, try again
                    case 103:
                    case 104:
                        return decrypt(values, true);
                    // missing private key
                    case 102:
                        wait = true;
                        smsSync.runOnUiThread(new Runnable() {
                            public void run() {
                                AlertDialog diag = getPgpPrivateKeyMissingDialog().create();
                                diag.setOnDismissListener(new DialogInterface.OnDismissListener() {
                                    public void onDismiss(DialogInterface dialog) {
                                        wait = false;
                                    }
                                });
                                diag.show();
                            }
                        });
                        pgpKeysToSkip.add(pgpKey);
                        return false;

                    default:
                       canceled = true;
                       throw new MessagingException("could not decrypt body");

                }
            } else {
                values.put(SmsConsts.BODY, mEnc.get_result());
                values.remove(SmsConsts.PGP);
                return true;
            }
        }

        private AlertDialog.Builder getPgpPrivateKeyMissingDialog() {
            return new AlertDialog.Builder(smsSync)
                .setTitle(getString(R.string.ui_dialog_private_key_missing_title))
                .setMessage(getString(R.string.ui_dialog_private_key_missing_msg))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) { dialog.cancel(); }
                });
        }

        private AlertDialog.Builder getAskForPgpPassphraseDialog(final String key, boolean lastKeyWrong) {
            AlertDialog.Builder alert = new AlertDialog.Builder(smsSync)
                    .setTitle(getString(R.string.ui_dialog_ask_pgp_passphrase_title));

            String defaultMsg = getString(R.string.ui_dialog_ask_pgp_passphrase_msg, key);
            if (lastKeyWrong) {
                alert.setMessage(defaultMsg+"\n\n"+
                                 getString(R.string.ui_dialog_ask_pgp_passphrase_last_key_wrong));
            } else {
                alert.setMessage(defaultMsg);
            }

            final EditText input = new EditText(smsSync);
            input.setTransformationMethod(new PasswordTransformationMethod());
            alert.setView(input);
            alert.setPositiveButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    pgpKeyPassphrases.put(key, input.getText().toString());
                }
            });

            alert.setNegativeButton(getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    canceled = true;
                }
            });

            alert.setNeutralButton(getString(R.string.ui_dialog_ask_pgp_passphrase_button_skip_this_key), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    pgpKeysToSkip.add(key);
                }
            });
            return alert;
        }
    }

    private synchronized void asyncClearCache() {
       new Thread("clearCache") {
          @Override public void run() { clearCache(); }
       }.start();
    }

    private void clearCache() {
        File tmp = getCacheDir();
        if (tmp != null) { /* not sure why this would return null */
            Log.d(TAG, "clearing cache in " + tmp);
            for (File f : tmp.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.startsWith("body");
                }
            })) {
                if (LOCAL_LOGV) Log.v(TAG, "deleting " + f);
                if (!f.delete()) Log.w(TAG, "error deleting " + f);
            }
        }
    }

    private boolean callLogExists(ContentValues values) {
        Cursor c = getContentResolver().query(CALLLOG_PROVIDER,
                new String[] { "_id" },
                "number = ? AND duration = ? AND type = ?",
                new String[] { values.getAsString(CallLog.Calls.NUMBER),
                               values.getAsString(CallLog.Calls.DURATION),
                               values.getAsString(CallLog.Calls.TYPE) },
                               null
        );
        boolean exists = false;
        if (c != null) {
          exists = c.getCount() > 0;
          c.close();
        }
        return exists;
    }

    private boolean smsExists(ContentValues values) {
        // just assume equality on date+address+type
        Cursor c = getContentResolver().query(SMS_PROVIDER,
                new String[] { "_id" },
                "date = ? AND address = ? AND type = ?",
                new String[] { values.getAsString(SmsConsts.DATE),
                               values.getAsString(SmsConsts.ADDRESS),
                               values.getAsString(SmsConsts.TYPE)},
                               null
        );

        boolean exists = false;
        if (c != null) {
          exists = c.getCount() > 0;
          c.close();
        }
        return exists;
    }
}
