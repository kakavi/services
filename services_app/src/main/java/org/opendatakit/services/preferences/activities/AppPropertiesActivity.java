/*
 * Copyright (C) 2016 University of Washington
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

package org.opendatakit.services.preferences.activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.opendatakit.activities.IAppAwareActivity;
import org.opendatakit.consts.IntentConsts;
import org.opendatakit.properties.CommonToolProperties;
import org.opendatakit.properties.PropertiesSingleton;
import org.opendatakit.services.R;
import org.opendatakit.services.preferences.PreferenceViewModel;
import org.opendatakit.services.preferences.fragments.AdminConfigurableDeviceSettingsFragment;
import org.opendatakit.services.preferences.fragments.AdminConfigurableServerSettingsFragment;
import org.opendatakit.services.preferences.fragments.AdminConfigurableTablesSettingsFragment;
import org.opendatakit.services.preferences.fragments.AdminPasswordChallengeFragment;
import org.opendatakit.services.preferences.fragments.AdminPasswordSettingsFragment;
import org.opendatakit.services.preferences.fragments.DeviceSettingsFragment;
import org.opendatakit.services.preferences.fragments.ServerSettingsFragment;
import org.opendatakit.services.preferences.fragments.SettingsMenuFragment;
import org.opendatakit.services.preferences.fragments.TablesSettingsFragment;
import org.opendatakit.services.sync.actions.activities.VerifyServerSettingsActivity;
import org.opendatakit.utilities.ODKFileUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * App-level settings activity.  Used across all tools.
 *
 * @author mitchellsundt@gmail.com
 */
public class AppPropertiesActivity extends AppCompatActivity implements
    IOdkAppPropertiesActivity,
    IAppAwareActivity,
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

  private static final String t = "AppPropertiesActivity";

  public static final int SPLASH_IMAGE_CHOOSER = 1;

  private static final String SAVED_ADMIN_CONFIGURED = "savedAdminConfigured";
  private String mAppName;
  private boolean mAdminMode;
  private boolean mAdminConfigured;
  private Activity mActivity = this;
  
  private PropertiesSingleton mProps;
  private PreferenceViewModel preferenceViewModel;

  public PropertiesSingleton getProps() {
    return mProps;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_app_properties);

    mAppName = getIntent().getStringExtra(IntentConsts.INTENT_KEY_APP_NAME);
    if (mAppName == null || mAppName.length() == 0) {
      mAppName = ODKFileUtils.getOdkDefaultAppName();
    }

    mProps = CommonToolProperties.get(this, mAppName);

    Map<String,String> properties = new HashMap<String,String>();
    properties.put(CommonToolProperties.KEY_SYNC_SERVER_URL, getString(R.string.sync_default_server_url));
    mProps.setProperties(properties);
    mProps.signalPropertiesChange();


    String adminPwd = mProps.getProperty(CommonToolProperties.KEY_ADMIN_PW);
    mAdminConfigured = (adminPwd != null && adminPwd.length() != 0);

    mAdminMode =
        getIntent().getBooleanExtra(IntentConsts.INTENT_KEY_SETTINGS_IN_ADMIN_MODE, false);

    preferenceViewModel = ViewModelProviders
        .of(this)
        .get(PreferenceViewModel.class);

    preferenceViewModel.getAdminConfigured().setValue(mAdminConfigured);
    preferenceViewModel.getAdminMode().setValue(mAdminMode);

    preferenceViewModel.getAdminMode().observe(this, new Observer<Boolean>() {
      @Override
      public void onChanged(Boolean adminMode) {
        int titleResId = adminMode ?
            R.string.action_bar_general_settings_admin_mode :
            R.string.action_bar_general_settings;

        getSupportActionBar().setTitle(getString(titleResId, getAppName()));
      }
    });

    if (savedInstanceState == null) {
      getSupportFragmentManager()
          .beginTransaction()
          .replace(R.id.app_properties_content, new SettingsMenuFragment())
          .commit();
    }
  }

  @Override protected void onResume() {
    super.onResume();

    mAppName = this.getIntent().getStringExtra(IntentConsts.INTENT_KEY_APP_NAME);
    if (mAppName == null || mAppName.length() == 0) {
      mAppName = ODKFileUtils.getOdkDefaultAppName();
    }

    mProps = CommonToolProperties.get(this, mAppName);
  }

  @Override
  public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
    Fragment prefFragment;
    if (pref.getFragment().equals(ServerSettingsFragment.class.getName())) {
      // When the user first presses for the Server Settings button,
      // the firstLaunch boolean should be set to false so that the config screen
      // will no longer prompt the user to set their configuration (since they already have)
      boolean isFirstLaunch = mProps.getBooleanProperty(CommonToolProperties.KEY_FIRST_LAUNCH);
      if (isFirstLaunch) {
        // the user has not entered the sync screen before entering the configure screen.
        // set FirstLaunch to false
        mProps.setProperties(Collections.singletonMap(CommonToolProperties
                .KEY_FIRST_LAUNCH, "false"));
      }
      prefFragment = new ServerSettingsFragment();
    } else if (pref.getFragment().equals(DeviceSettingsFragment.class.getName())) {
      prefFragment = new DeviceSettingsFragment();
    } else if (pref.getFragment().equals(TablesSettingsFragment.class.getName())) {
      prefFragment = new TablesSettingsFragment();
    } else if (pref.getFragment().equals(AdminPasswordSettingsFragment.class.getName())) {
      prefFragment = new AdminPasswordSettingsFragment();
    } else if (pref.getFragment().equals(AdminConfigurableServerSettingsFragment.class.getName())) {
      prefFragment = new AdminConfigurableServerSettingsFragment();
    } else if (pref.getFragment().equals(AdminConfigurableDeviceSettingsFragment.class.getName())) {
      prefFragment = new AdminConfigurableDeviceSettingsFragment();
    } else if (pref.getFragment().equals(AdminConfigurableTablesSettingsFragment.class.getName())) {
      prefFragment = new AdminConfigurableTablesSettingsFragment();
    } else if (pref.getFragment().equals(AdminPasswordChallengeFragment.class.getName())) {
      prefFragment = new AdminPasswordChallengeFragment();
    } else {
      // unrecognized fragment
      return false;
    }

    Bundle bundle = new Bundle();
    bundle.putBoolean(
        IntentConsts.INTENT_KEY_SETTINGS_IN_ADMIN_MODE,
        preferenceViewModel.getAdminMode().getValue()
    );
    prefFragment.setArguments(bundle);

    getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.app_properties_content, prefFragment)
        .addToBackStack(null)
        .commit();

    return true;
  }

  /**
   * If we are exiting the non-privileged settings screen and the user roles are not
   * set, then prompt the user to verify user permissions or lose their changes
   *
   * Otherwise, exit the settings screen.
   */
  @Override
  public void onBackPressed() {
    if ( !mAdminMode ) {
      String authType = mProps.getProperty(CommonToolProperties.KEY_AUTHENTICATION_TYPE);
      boolean isAnonymous = (authType == null) || (authType.length() == 0) ||
              getString(R.string.credential_type_none).equals(authType);
      if ( mProps.getProperty(CommonToolProperties.KEY_ROLES_LIST).length() == 0 &&
              !isAnonymous ) {

        promptToVerifyCredentials();
        return;
      }
    }
    super.onBackPressed();
  }

  private void promptToVerifyCredentials() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.authenticate_credentials);
    builder.setMessage(R.string.anonymous_warning);
    builder.setPositiveButton(R.string.new_user, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        // this will swap to the new activity and close this one
        Intent i = new Intent(mActivity, VerifyServerSettingsActivity.class);
        i.putExtra(IntentConsts.INTENT_KEY_APP_NAME, mAppName);
        startActivity(i);
        dialog.dismiss();
      }
    });
    builder.setNegativeButton(R.string.logout, new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        finish();
        dialog.dismiss();
      }
    });
    AlertDialog dialog = builder.create();
    dialog.show();
  }

  public String getAppName() {
    return mAppName;
  }
}
