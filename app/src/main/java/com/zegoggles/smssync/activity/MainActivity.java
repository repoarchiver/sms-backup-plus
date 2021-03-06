/* Copyright (c) 2009 Christoph Studer <chstuder@gmail.com>
 * Copyright (c) 2010 Jan Berkel <jan.berkel@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zegoggles.smssync.activity;

import android.annotation.TargetApi;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony.Sms;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentManager.BackStackEntry;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.support.v7.preference.PreferenceFragmentCompat.OnPreferenceStartFragmentCallback;
import android.support.v7.preference.PreferenceFragmentCompat.OnPreferenceStartScreenCallback;
import android.support.v7.preference.PreferenceScreen;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import com.squareup.otto.Subscribe;
import com.zegoggles.smssync.App;
import com.zegoggles.smssync.Consts;
import com.zegoggles.smssync.R;
import com.zegoggles.smssync.activity.Dialogs.WebConnect;
import com.zegoggles.smssync.activity.Dialogs.SmsDefaultPackage;
import com.zegoggles.smssync.activity.auth.AccountManagerAuthActivity;
import com.zegoggles.smssync.activity.auth.OAuth2WebAuthActivity;
import com.zegoggles.smssync.activity.events.AccountAddedEvent;
import com.zegoggles.smssync.activity.events.AccountConnectionChangedEvent;
import com.zegoggles.smssync.activity.events.FallbackAuthEvent;
import com.zegoggles.smssync.activity.events.PerformAction;
import com.zegoggles.smssync.activity.events.PerformAction.Actions;
import com.zegoggles.smssync.activity.fragments.MainSettings;
import com.zegoggles.smssync.auth.OAuth2Client;
import com.zegoggles.smssync.preferences.AuthPreferences;
import com.zegoggles.smssync.preferences.BackupManagerWrapper;
import com.zegoggles.smssync.preferences.Preferences;
import com.zegoggles.smssync.service.BackupType;
import com.zegoggles.smssync.service.SmsBackupService;
import com.zegoggles.smssync.service.SmsRestoreService;
import com.zegoggles.smssync.service.state.RestoreState;
import com.zegoggles.smssync.tasks.OAuth2CallbackTask;
import com.zegoggles.smssync.utils.BundleBuilder;

import static android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT;
import static android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME;
import static android.support.v7.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT;
import static android.widget.Toast.LENGTH_LONG;
import static com.zegoggles.smssync.App.LOCAL_LOGV;
import static com.zegoggles.smssync.App.TAG;
import static com.zegoggles.smssync.activity.Dialogs.ConfirmAction.ACTION;
import static com.zegoggles.smssync.activity.Dialogs.Type.OAUTH2_ACCESS_TOKEN_ERROR;
import static com.zegoggles.smssync.activity.Dialogs.WebConnect.REQUEST_WEB_AUTH;
import static com.zegoggles.smssync.activity.Dialogs.FirstSync.MAX_ITEMS_PER_SYNC;
import static com.zegoggles.smssync.activity.Dialogs.MissingCredentials.USE_XOAUTH;
import static com.zegoggles.smssync.activity.Dialogs.SmsDefaultPackage.REQUEST_CHANGE_DEFAULT_SMS_PACKAGE;
import static com.zegoggles.smssync.activity.Dialogs.Type.ABOUT;
import static com.zegoggles.smssync.activity.Dialogs.Type.OAUTH2_ACCESS_TOKEN_PROGRESS;
import static com.zegoggles.smssync.activity.Dialogs.Type.ACCOUNT_MANAGER_TOKEN_ERROR;
import static com.zegoggles.smssync.activity.Dialogs.Type.CONFIRM_ACTION;
import static com.zegoggles.smssync.activity.Dialogs.Type.WEB_CONNECT;
import static com.zegoggles.smssync.activity.Dialogs.Type.DISCONNECT;
import static com.zegoggles.smssync.activity.Dialogs.Type.FIRST_SYNC;
import static com.zegoggles.smssync.activity.Dialogs.Type.MISSING_CREDENTIALS;
import static com.zegoggles.smssync.activity.Dialogs.Type.RESET;
import static com.zegoggles.smssync.activity.Dialogs.Type.SMS_DEFAULT_PACKAGE_CHANGE;
import static com.zegoggles.smssync.activity.Dialogs.Type.UPGRADE_FROM_SMSBACKUP;
import static com.zegoggles.smssync.activity.Dialogs.Type.VIEW_LOG;
import static com.zegoggles.smssync.activity.auth.AccountManagerAuthActivity.ACTION_ADD_ACCOUNT;
import static com.zegoggles.smssync.activity.auth.AccountManagerAuthActivity.ACTION_FALLBACK_AUTH;
import static com.zegoggles.smssync.activity.auth.AccountManagerAuthActivity.EXTRA_ACCOUNT;
import static com.zegoggles.smssync.activity.auth.AccountManagerAuthActivity.EXTRA_TOKEN;
import static com.zegoggles.smssync.activity.events.PerformAction.Actions.Backup;
import static com.zegoggles.smssync.activity.events.PerformAction.Actions.BackupSkip;

/**
 * This is the main activity showing the status of the SMS Sync service and
 * providing controls to configure it.
 */
public class MainActivity extends AppCompatActivity implements
        OnPreferenceStartFragmentCallback,
        OnPreferenceStartScreenCallback,
        FragmentManager.OnBackStackChangedListener {
    private static final int REQUEST_PICK_ACCOUNT = 2;
    private static final String SCREEN_TITLE = "title";

    private Preferences preferences;
    private AuthPreferences authPreferences;
    private OAuth2Client oauth2Client;
    private Intent fallbackAuthIntent;
    private Intent changeDefaultPackageIntent;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);

        authPreferences = new AuthPreferences(this);
        oauth2Client = new OAuth2Client(authPreferences.getOAuth2ClientId());
        fallbackAuthIntent = new Intent(this, OAuth2WebAuthActivity.class).setData(oauth2Client.requestUrl());
        changeDefaultPackageIntent = new Intent(ACTION_CHANGE_DEFAULT).putExtra(EXTRA_PACKAGE_NAME, getPackageName());

        preferences = new Preferences(this);
        preferences.getSharedPreferences().registerOnSharedPreferenceChangeListener(
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    BackupManagerWrapper.dataChanged(MainActivity.this);
                }
            }
        );
        showFragment(new MainSettings(), null);
        if (preferences.shouldShowUpgradeMessage()) {
            showDialog(UPGRADE_FROM_SMSBACKUP);
        }
        if (preferences.shouldShowAboutDialog()) {
            showDialog(ABOUT);
        }
        checkDefaultSmsApp();
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.register(this);
    }

    @Override
    protected void onStop() {
        App.unregister(this);
        super.onStop();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_about:
                showDialog(ABOUT);
                return true;
            case R.id.menu_reset:
                showDialog(RESET);
                return true;
            case R.id.menu_view_log:
                showDialog(VIEW_LOG);

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult(" + requestCode + "," + resultCode + "," + data + ")");

        switch (requestCode) {
            case REQUEST_CHANGE_DEFAULT_SMS_PACKAGE: {
                if (resultCode == RESULT_CANCELED) break;
                preferences.setSeenSmsDefaultPackageChangeDialog();
                if (preferences.getSmsDefaultPackage() != null) {
                    startRestore();
                }
                break;
            }
            case REQUEST_WEB_AUTH: {
                if (resultCode == RESULT_CANCELED) {
                    Toast.makeText(this, R.string.ui_dialog_access_token_error_msg, LENGTH_LONG).show();
                    return;
                }

                final String code = data == null ? null : data.getStringExtra(OAuth2WebAuthActivity.EXTRA_CODE);
                if (!TextUtils.isEmpty(code)) {
                    showDialog(OAUTH2_ACCESS_TOKEN_PROGRESS);
                    new OAuth2CallbackTask(oauth2Client).execute(code);
                } else {
                    showDialog(OAUTH2_ACCESS_TOKEN_ERROR);
                }
                break;
            }
            case REQUEST_PICK_ACCOUNT: {
                if (resultCode == RESULT_OK && data != null) {
                    if (ACTION_ADD_ACCOUNT.equals(data.getAction())) {
                        handleAccountManagerAuth(data);
                    } else if (ACTION_FALLBACK_AUTH.equals(data.getAction())) {
                        handleFallbackAuth(new FallbackAuthEvent(true));
                    }
                } else if (LOCAL_LOGV) {
                    Log.v(TAG, "request canceled, result="+resultCode);
                }
                break;
            }
        }
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference preference) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "onPreferenceStartFragment(" + preference + ")");
        }
        final Fragment fragment = Fragment.instantiate(this, preference.getFragment(),
                new BundleBuilder().putString(SCREEN_TITLE, String.valueOf(preference.getTitle())).build());
        showFragment(fragment, preference.getKey());
        return true;
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen preference) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "onPreferenceStartScreen(" + preference + ")");
        }
        // API level 9 compatibility
        if (preference.getFragment() == null) {
            preference.setFragment(preference.getKey());
            return onPreferenceStartFragment(caller, preference);
        } else {
            return false;
        }
    }

    @Subscribe public void restoreStateChanged(final RestoreState newState) {
        if (isSmsBackupDefaultSmsApp() && newState.isFinished()) {
            restoreDefaultSmsProvider(preferences.getSmsDefaultPackage());
        }
    }

    @Subscribe public void onOAuth2Callback(OAuth2CallbackTask.OAuth2CallbackEvent event) {
        if (event.valid()) {
            authPreferences.setOauth2Token(event.token.userName, event.token.accessToken, event.token.refreshToken);
            onAuthenticated();
        } else {
            showDialog(OAUTH2_ACCESS_TOKEN_ERROR);
        }
    }

    @Subscribe public void onConnect(AccountConnectionChangedEvent event) {
        if (event.connected) {
            startActivityForResult(new Intent(this,
                    AccountManagerAuthActivity.class), REQUEST_PICK_ACCOUNT);
        } else {
            showDialog(DISCONNECT);
        }
    }

    @Subscribe public void handleFallbackAuth(FallbackAuthEvent event) {
        if (event.showDialog) {
            showDialog(WEB_CONNECT);
        } else {
            startActivityForResult(fallbackAuthIntent, REQUEST_WEB_AUTH);
        }
    }

    @Override public void onBackStackChanged() {
        getSupportActionBar().setSubtitle(getCurrentTitle());
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
    }

    private @Nullable CharSequence getCurrentTitle() {
        final int entryCount = getSupportFragmentManager().getBackStackEntryCount();
        if (entryCount == 0) {
            return null;
        } else {
            final BackStackEntry entry = getSupportFragmentManager().getBackStackEntryAt(entryCount - 1);
            return entry.getBreadCrumbTitle();
        }
    }

    private void onAuthenticated() {
        App.post(new AccountAddedEvent());
        // Invite user to perform a backup, but only once
        if (preferences.isFirstUse()) {
            showDialog(FIRST_SYNC);
        }
    }

    @Subscribe public void performAction(PerformAction action) {
        if (authPreferences.isLoginInformationSet()) {
            if (action.confirm) {
                showDialog(CONFIRM_ACTION, new BundleBuilder().putString(ACTION, action.action.name()).build());
            } else if (preferences.isFirstBackup() && action.action == Backup) {
                showDialog(FIRST_SYNC);
            } else {
                doPerform(action.action);
            }
        } else {
            showDialog(MISSING_CREDENTIALS);
        }
    }

    @Subscribe public void doPerform(Actions action) {
        switch (action) {
            case Backup:
            case BackupSkip:
                startBackup(action == BackupSkip);
                break;
            case Restore:
                startRestore();
                break;
        }
    }

    private void startBackup(boolean skip) {
        final Intent intent = new Intent(this, SmsBackupService.class);
        if (preferences.isFirstBackup()) {
            intent.putExtra(Consts.KEY_SKIP_MESSAGES, skip);
        }
        intent.putExtra(BackupType.EXTRA, BackupType.MANUAL.name());
        startService(intent);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void startRestore() {
        final Intent intent = new Intent(this, SmsRestoreService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (isSmsBackupDefaultSmsApp()) {
                startService(intent);
            } else {
                final String defaultSmsPackage = Sms.getDefaultSmsPackage(this);
                Log.d(TAG, "default SMS package: " + defaultSmsPackage);
                preferences.setSmsDefaultPackage(defaultSmsPackage);

                if (preferences.hasSeenSmsDefaultPackageChangeDialog()) {
                    requestDefaultSmsPackageChange();
                } else {
                    showDialog(SMS_DEFAULT_PACKAGE_CHANGE);
                }
            }
        } else {
            startService(intent);
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private boolean isSmsBackupDefaultSmsApp() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                getPackageName().equals(Sms.getDefaultSmsPackage(this));
    }

    private void showFragment(@NonNull Fragment fragment, @Nullable String rootKey) {
        Bundle args = fragment.getArguments() == null ? new Bundle() : fragment.getArguments();
        args.putString(ARG_PREFERENCE_ROOT, rootKey);
        fragment.setArguments(args);
        FragmentTransaction tx = getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.preferences_container, fragment, rootKey);
        if (rootKey != null) {
            tx.addToBackStack(null);
            tx.setBreadCrumbTitle(args.getString(SCREEN_TITLE));
        }
        tx.commit();
    }

    private void showDialog(Dialogs.Type dialog) {
        final Bundle arguments = new Bundle();
        switch (dialog) {
            case FIRST_SYNC:
                arguments.putInt(MAX_ITEMS_PER_SYNC, preferences.getMaxItemsPerSync()); break;
            case MISSING_CREDENTIALS:
                arguments.putBoolean(USE_XOAUTH, authPreferences.useXOAuth()); break;
            case WEB_CONNECT:
                arguments.putParcelable(WebConnect.INTENT, fallbackAuthIntent); break;
            case SMS_DEFAULT_PACKAGE_CHANGE:
                arguments.putParcelable(SmsDefaultPackage.INTENT, changeDefaultPackageIntent); break;
        }
        showDialog(dialog, arguments);
    }

    private void showDialog(@NonNull Dialogs.Type dialog, @Nullable Bundle args) {
        dialog.instantiate(this, args).show(getSupportFragmentManager(), dialog.name());
    }

    private void requestDefaultSmsPackageChange() {
        startActivityForResult(changeDefaultPackageIntent, REQUEST_CHANGE_DEFAULT_SMS_PACKAGE);
    }

    private void restoreDefaultSmsProvider(String smsPackage) {
        Log.d(TAG, "restoring SMS provider "+smsPackage);
        if (!TextUtils.isEmpty(smsPackage)) {
            final Intent changeDefaultIntent = new Intent(ACTION_CHANGE_DEFAULT)
                    .putExtra(EXTRA_PACKAGE_NAME, smsPackage);

            startActivity(changeDefaultIntent);
        }
    }

    private void handleAccountManagerAuth(@NonNull Intent data) {
        final String token = data.getStringExtra(EXTRA_TOKEN);
        final String account = data.getStringExtra(EXTRA_ACCOUNT);
        if (!TextUtils.isEmpty(token) && !TextUtils.isEmpty(account)) {
            authPreferences.setOauth2Token(account, token, null);
            onAuthenticated();
        } else {
            String error = data.getStringExtra(AccountManagerAuthActivity.EXTRA_ERROR);
            if (!TextUtils.isEmpty(error)) {
                showDialog(ACCOUNT_MANAGER_TOKEN_ERROR);
            }
        }
    }

    private void checkDefaultSmsApp() {
        if (isSmsBackupDefaultSmsApp() && !SmsRestoreService.isServiceWorking()) {
            restoreDefaultSmsProvider(preferences.getSmsDefaultPackage());
        }
    }
}
