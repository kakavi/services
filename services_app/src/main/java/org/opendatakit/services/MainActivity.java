/*
 * Copyright (C) 2015 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.services;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.work.*;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;
import org.opendatakit.activities.IAppAwareActivity;
import org.opendatakit.consts.IntentConsts;
import org.opendatakit.consts.RequestCodeConsts;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.properties.CommonToolProperties;
import org.opendatakit.properties.PropertiesSingleton;
import org.opendatakit.services.database.AndroidConnectFactory;
import org.opendatakit.services.preferences.activities.AppPropertiesActivity;
import org.opendatakit.services.preferences.fragments.ServerSettingsFragment;
import org.opendatakit.services.resolve.conflict.AllConflictsResolutionActivity;
import org.opendatakit.services.sync.actions.activities.*;
import org.opendatakit.services.sync.actions.fragments.SyncFragment;
import org.opendatakit.services.sync.service.OdkSyncJob;
import org.opendatakit.services.utilities.ODKServicesPropertyUtils;
import org.opendatakit.sync.service.IOdkSyncServiceInterface;
import org.opendatakit.sync.service.SyncAttachmentState;
import org.opendatakit.utilities.ODKFileUtils;
import org.opendatakit.utilities.RuntimePermissionUtils;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AbsSyncBaseActivity implements IAppAwareActivity,
    ActivityCompat.OnRequestPermissionsResultCallback {

  private AlertDialog mDialog;
  final Handler handler = new Handler();

  // Used for logging
  @SuppressWarnings("unused") private static final String TAG = MainActivity.class.getSimpleName();

  private static final int EXT_STORAGE_REQ_CODE = 0;

  protected static final String[] REQUIRED_PERMISSIONS = new String[] {
      Manifest.permission.READ_EXTERNAL_STORAGE,
      Manifest.permission.WRITE_EXTERNAL_STORAGE
  };

  private int SYNC_ACTIVITY_RESULT_CODE = 10;
  private int VERIFY_SERVER_SETTINGS_ACTIVITY_RESULT_CODE = 20;
  private int RESOLVE_CONFLICT_ACTIVITY_RESULT_CODE = 30;
  private int SETTINGS_ACTIVITY_RESULT_CODE = 100;

  protected String appName;
  protected PropertiesSingleton props;
  private boolean permissionOnly;
  private WorkManager mWorkManager;

  private IOdkSyncServiceInterface iOdkSyncServiceInterface;

  @Override
  protected void onDestroy() {
    super.onDestroy();
    WebLogger.closeAll();
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // IMPORTANT NOTE: the Application object is not yet created!

    // Used to ensure that the singleton has been initialized properly
    AndroidConnectFactory.configure();

    this.permissionOnly = getIntent().getBooleanExtra(IntentConsts.INTENT_KEY_PERMISSION_ONLY, false);

    if (!RuntimePermissionUtils.checkSelfAllPermission(this, REQUIRED_PERMISSIONS)) {
      ActivityCompat.requestPermissions(
          this,
          REQUIRED_PERMISSIONS,
          EXT_STORAGE_REQ_CODE
      );
    }

    //background service
    mWorkManager = WorkManager.getInstance();
    startBackgroundJob();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      requestUnknownSrceInstall();
    } else {
      try{
        boolean isNonPlayAppAllowed = Settings.Secure.getInt(getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS) == 1;
        if (!isNonPlayAppAllowed) {
          startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
      } catch (Exception e){
        e.printStackTrace();
      }
    }

    launch();

    //firebase
    FirebaseInstanceId.getInstance().getInstanceId()
            .addOnCompleteListener(new OnCompleteListener<InstanceIdResult>() {
              @Override
              public void onComplete(@NonNull Task<InstanceIdResult> task) {
                if (!task.isSuccessful()) {
                  Log.w(TAG, "getInstanceId failed", task.getException( ));
                  return;
                }

                // Get new Instance ID token
                String token = task.getResult().getToken();

                // Log and toast
                String msg = "Firebase token:" + token;
                Log.d(TAG, msg);
              }
            });
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  private void requestUnknownSrceInstall() {
    if(!getPackageManager().canRequestPackageInstalls()){
      Toast.makeText(this, "Please allow Kenga Services to install from unknown sources", Toast.LENGTH_SHORT).show();

      new Handler().postDelayed(new Runnable() {
        @Override
        public void run() {
          startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:org.opendatakit.services")));
        }
      }, 2000);

    }
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  @Override
  protected void onResume() {
    super.onResume();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      requestUnknownSrceInstall();
    } else {
      try{
        boolean isNonPlayAppAllowed = Settings.Secure.getInt(getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS) == 1;
        if (!isNonPlayAppAllowed) {
          startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
      } catch (Exception e){
        e.printStackTrace();
      }
    }
    launch();
  }

  private void launch() {

    appName = getIntent().getStringExtra(IntentConsts.INTENT_KEY_APP_NAME);
    if (appName == null) {
      appName = ODKFileUtils.getOdkDefaultAppName();
    }

    //check if apps installed
    boolean isIOInstalled = ODKServicesPropertyUtils.isPackageInstalled("org.openintents.filemanager", this.getPackageManager());
    boolean isSurveyInstalled = ODKServicesPropertyUtils.isPackageInstalled("org.opendatakit.survey", this.getPackageManager());
    boolean isTablesInstalled = ODKServicesPropertyUtils.isPackageInstalled("org.opendatakit.tables", this.getPackageManager());

    if(isIOInstalled || isSurveyInstalled || isTablesInstalled) {
      //installed
      //hide app
/*      PackageManager p = getPackageManager();
      p.setComponentEnabledSetting(getComponentName(), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);*/

    } else {
      //not installed
      //show app
/*      PackageManager p = getPackageManager();
      ComponentName componentName = new ComponentName(this, MainActivity.class);
      p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);*/

      Toast.makeText(this, "Please install app to continue", Toast.LENGTH_SHORT).show();
      Intent i = new Intent(this, VerifyServerSettingsActivity.class);
      i.putExtra(IntentConsts.INTENT_KEY_APP_NAME, appName);
      startActivity(i);
    }

    firstLaunch();
    WebLogger.getLogger(getAppName()).i(TAG, "[onResume] getting SyncFragment");


    FragmentManager mgr = getSupportFragmentManager();
    String newFragmentName;
    Fragment newFragment;

    // we want the list fragment
    newFragmentName = SyncFragment.NAME;
    newFragment = mgr.findFragmentByTag(newFragmentName);
    if ( newFragment == null ) {
      newFragment = new SyncFragment();
      WebLogger.getLogger(getAppName()).i(TAG, "[onResume] creating new SyncFragment");

      FragmentTransaction trans = mgr.beginTransaction();
      trans.replace(R.id.sync_activity_view, newFragment, newFragmentName);
      WebLogger.getLogger(getAppName()).i(TAG, "[onResume] replacing fragment with id " + newFragment.getId());
      trans.commit();
    }

  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();

    if (id == R.id.menu_table_home) {

      try {
        Intent intent = new Intent();
        intent.setComponent(
                new ComponentName("org.opendatakit.tables", "org.opendatakit.tables.activities.Launcher"));
        intent.setAction(Intent.ACTION_DEFAULT);
        Bundle bundle = new Bundle();
        bundle.putString(IntentConsts.INTENT_KEY_APP_NAME, appName);
        intent.putExtras(bundle);
        this.startActivityForResult(intent, RequestCodeConsts.RequestCodes.LAUNCH_SYNC);
      } catch (ActivityNotFoundException e) {
        WebLogger.getLogger(appName).printStackTrace(e);
        Toast.makeText(this, "Everflow is not installed", Toast.LENGTH_LONG).show();
      }
      return true;
    }

   /* if (id == R.id.action_sync) {
      Intent i = new Intent(this, SyncActivity.class);
      i.putExtra(IntentConsts.INTENT_KEY_APP_NAME, getAppName());
      startActivityForResult(i, SYNC_ACTIVITY_RESULT_CODE);
      return true;
    }

    if (id == R.id.action_verify_server_settings) {
      Intent i = new Intent(this, VerifyServerSettingsActivity.class);
      i.putExtra(IntentConsts.INTENT_KEY_APP_NAME, getAppName());
      startActivityForResult(i, VERIFY_SERVER_SETTINGS_ACTIVITY_RESULT_CODE);
      return true;
    }*/

      if (id == R.id.menu_table_manager_sync) {
              Intent syncIntent = new Intent();
              syncIntent.setComponent(
                      new ComponentName(IntentConsts.Sync.APPLICATION_NAME, "org.opendatakit.services.MainActivity"));
              syncIntent.setAction(Intent.ACTION_DEFAULT);
              Bundle bundle = new Bundle();
              bundle.putString(IntentConsts.INTENT_KEY_APP_NAME, appName);
              syncIntent.putExtras(bundle);
              this.startActivityForResult(syncIntent, RequestCodeConsts.RequestCodes.LAUNCH_SYNC);
          return true;
      }

    if (id == R.id.action_resolve_conflict) {
      Intent i = new Intent(this, AllConflictsResolutionActivity.class);
      i.putExtra(IntentConsts.INTENT_KEY_APP_NAME, getAppName());
      startActivityForResult(i, RESOLVE_CONFLICT_ACTIVITY_RESULT_CODE);
      return true;
    }

   /* if (id == R.id.action_about) {

      FragmentManager mgr = getSupportFragmentManager();
      Fragment newFragment = mgr.findFragmentByTag(AboutMenuFragment.NAME);
      if (newFragment == null) {
        newFragment = new AboutMenuFragment();
      }
      FragmentTransaction trans = mgr.beginTransaction();
      trans.replace(R.id.main_activity_view, newFragment, AboutMenuFragment.NAME);
      trans.addToBackStack(AboutMenuFragment.NAME);
      trans.commit();

      return true;
    }*/

    if (id == R.id.action_change_user) {

      Intent i = new Intent(this, LoginActivity.class);
      i.putExtra(IntentConsts.INTENT_KEY_APP_NAME, mAppName);
      startActivity(i);
      return true;
    }

    if (id == R.id.action_settings) {

      Intent intent = new Intent(this, AppPropertiesActivity.class);
      intent.putExtra(IntentConsts.INTENT_KEY_APP_NAME, getAppName());
      startActivityForResult(intent, SETTINGS_ACTIVITY_RESULT_CODE);
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  @Override
  public String getAppName() {
    return appName;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    if (requestCode != EXT_STORAGE_REQ_CODE) {
      return;
    }

    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      if (permissionOnly) {
        setResult(Activity.RESULT_OK);
        finish();
      }
      return;
    }

    if (RuntimePermissionUtils.shouldShowAnyPermissionRationale(this, permissions)) {
      RuntimePermissionUtils.createPermissionRationaleDialog(this, requestCode, permissions)
          .setMessage(R.string.write_external_storage_rationale)
          .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
              dialog.cancel();
              setResult(Activity.RESULT_CANCELED);
              finish();
            }
          })
          .show();
    } else {
      Toast
          .makeText(this, R.string.write_external_perm_denied, Toast.LENGTH_LONG)
          .show();
      setResult(Activity.RESULT_CANCELED);
      finish();
    }
  }

  private void startBackgroundJob() {
    Log.i("SYNC: ","Background sync triggered!");

    // Create Network constraint
    Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();


    PeriodicWorkRequest periodicSyncDataWork =
            new PeriodicWorkRequest.Builder(OdkSyncJob.class, 15, TimeUnit.MINUTES)
                    .addTag("SyncData")
                    .setConstraints(constraints)
                    // setting a backoff on case the work needs to retry
                    .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .build();
    mWorkManager.enqueueUniquePeriodicWork(
            "SYNC_DATA",
            ExistingPeriodicWorkPolicy.KEEP, //Existing Periodic Work policy
            periodicSyncDataWork //work request
    );

  }

  public void performSync(SyncAttachmentState syncAttachmentState) {
    ((ISyncServiceInterfaceActivity) MainActivity.this)
            .invokeSyncInterfaceAction(new DoSyncActionCallback() {
              @Override public void doAction(IOdkSyncServiceInterface syncServiceInterface)
                      throws RemoteException {
                if (syncServiceInterface != null) {
                    syncServiceInterface.synchronizeWithServer(getAppName(), syncAttachmentState);
                } else {
                  WebLogger.getLogger(getAppName()).w(TAG, "[postTaskToAccessSyncService] syncServiceInterface == null");
                  // The service is not bound yet so now we need to try again
                  handler.postDelayed(new Runnable() {
                    @Override public void run() {
                      performSync(SyncAttachmentState.NONE);
                    }
                  }, 100);
                }
              }
            });
  }

  private void firstLaunch() {
    mProps = CommonToolProperties.get(this, appName);

    boolean isFirstLaunch = mProps.getBooleanProperty(CommonToolProperties.KEY_FIRST_LAUNCH);
    if (isFirstLaunch) {
      // set first launch to false
      mProps.setProperties(Collections.singletonMap(CommonToolProperties
              .KEY_FIRST_LAUNCH, "false"));
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      mDialog = builder.setMessage(R.string.configure_server_settings)
              .setCancelable(false)
              .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                  mDialog.dismiss();

                  getSupportFragmentManager()
                          .beginTransaction()
                          .replace(R.id.sync_activity_view, new ServerSettingsFragment())
                          .addToBackStack(null)
                          .commit();
                }
              })
              .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                  dialog.dismiss();
                }
              }).create();
      mDialog.setCanceledOnTouchOutside(false);
      mDialog.show();
    }
  }
}
