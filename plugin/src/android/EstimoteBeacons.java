/*
Android implementation of Cordova plugin for Estimote Beacons.

JavaDoc for Estimote Android API: https://estimote.github.io/Android-SDK/JavaDocs/
*/

package com.evothings;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;

import com.estimote.coresdk.cloud.api.CloudCallback;
import com.estimote.coresdk.cloud.api.EstimoteCloud;
import com.estimote.coresdk.common.config.EstimoteSDK;
import com.estimote.coresdk.common.exception.EstimoteCloudException;
import com.estimote.coresdk.common.requirements.SystemRequirementsHelper;
import com.estimote.coresdk.observation.region.Region;
import com.estimote.coresdk.cloud.model.*;
import com.estimote.coresdk.observation.region.beacon.BeaconRegion;
import com.estimote.coresdk.recognition.packets.Beacon;
import com.estimote.coresdk.recognition.packets.Nearable;
import com.estimote.coresdk.service.BeaconManager;
import com.estimote.mgmtsdk.common.exceptions.EstimoteDeviceException;
import com.estimote.mgmtsdk.connection.api.BeaconConnection;
import com.estimote.coresdk.observation.utils.Proximity;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.estimote.coresdk.common.config.EstimoteSDK.getAppId;
import static com.estimote.coresdk.common.config.EstimoteSDK.getAppToken;
import static com.estimote.coresdk.common.config.EstimoteSDK.getApplicationContext;
import static com.estimote.coresdk.observation.region.RegionUtils.computeAccuracy;
import static com.estimote.coresdk.observation.region.RegionUtils.computeProximity;

/**
 * Plugin class for the Estimote Beacon plugin.
 */
public class EstimoteBeacons extends CordovaPlugin
{
	private static final String LOGTAG = "EstimoteBeacons";
	private static final String ESTIMOTE_PROXIMITY_UUID = "B9407F30-F5F8-466E-AFF9-25556B57FE6D";
	private static final String ESTIMOTE_SAMPLE_REGION_ID = "EstimoteSampleRegion";
	private static final int REQUEST_ENABLE_BLUETOOTH = 1;

	private BeaconManager mBeaconManager;
	private EstimoteSDK mEstimoteSDK;
	private CordovaInterface  mCordovaInterface;

	private ArrayList<Beacon> mRangedBeacons;
	private BeaconConnected   mConnectedBeacon;
	private boolean           mIsConnected = false;


	// Maps and variables that keep track of Cordova callbacks.
	private HashMap<String, CallbackContext> mRangingCallbackContexts =
			new HashMap<String, CallbackContext>();
	private HashMap<String, CallbackContext> mMonitoringCallbackContexts =
			new HashMap<String, CallbackContext>();

	private CallbackContext   mBluetoothStateCallbackContext;
	private CallbackContext   mBeaconConnectionCallback;
	private CallbackContext   mBeaconDisconnectionCallback;

	// todo: consider using pluginInitialize instead, per Cordova recommendation
	//   https://github.com/apache/cordova-android/blob/master/framework/src/org/apache/cordova/CordovaPlugin.java#L60-L61
	/**
	 * Plugin initialiser.
	 */
	@Override
	public void initialize(final CordovaInterface cordova, CordovaWebView webView)
	{
		Log.i(LOGTAG, "initialize");

		super.initialize(cordova, webView);

		mCordovaInterface = cordova;
		mCordovaInterface.setActivityResultCallback(this);

		if (mBeaconManager == null) {
			mBeaconManager = new BeaconManager(cordova.getActivity());
		}

		mBeaconManager.setErrorListener(new BeaconManager.ErrorListener() {
			@Override
			public void onError(Integer errorId) {
				Log.e(LOGTAG, "BeaconManager error: " + errorId);
			}
		});

		mRangedBeacons = new ArrayList<Beacon>();
	}

	/**
	 * Plugin reset.
	 * Called when the WebView does a top-level navigation or refreshes.
	 */
	@Override
	public void onReset() {
		Log.i(LOGTAG, "onReset");

		disconnectBeaconManager();

		mRangingCallbackContexts = new HashMap<String, CallbackContext>();
		mMonitoringCallbackContexts = new HashMap<String, CallbackContext>();
	}

	/**
	 * The final call you receive before your activity is destroyed.
	  */
	public void onDestroy() {
		Log.i(LOGTAG, "onDestroy");
		disconnectConnectedBeacon();
		disconnectBeaconManager();
	}

	/**
	 * Disconnect from the beacon manager.
	  */
	private void disconnectBeaconManager() {
		if (mBeaconManager != null && mIsConnected) {
			mBeaconManager.disconnect();
			mIsConnected = false;
		}
	}

	/**
	 * Entry point for JavaScript calls.
	 */
	@Override
	public boolean execute(
			String action,
			CordovaArgs args,
			final CallbackContext callbackContext)
			throws JSONException
	{
		if ("beacons_startRangingBeaconsInRegion".equals(action)) {
			startRangingBeaconsInRegion(args, callbackContext);
		}
		else if ("beacons_stopRangingBeaconsInRegion".equals(action)) {
			stopRangingBeaconsInRegion(args, callbackContext);
		}
		else if ("beacons_startMonitoringForRegion".equals(action)) {
			startMonitoringForRegion(args, callbackContext);
		}
		else if ("beacons_stopMonitoringForRegion".equals(action)) {
			stopMonitoringForRegion(args, callbackContext);
		}
		else if ("beacons_setupAppIDAndAppToken".equals(action)) {
			setupAppIDAndAppToken(args, callbackContext);
		}
		else if ("beacons_connectToBeacon".equals(action)) {
			connectToBeacon(args, callbackContext);
		}
		else if ("beacons_disconnectConnectedBeacon".equals(action)) {
			disconnectConnectedBeacon(args, callbackContext);
		}
		else if ("beacons_writeConnectedProximityUUID".equals(action)) {
			writeConnectedProximityUUID(args, callbackContext);
		}
		else if ("beacons_writeConnectedMajor".equals(action)) {
			writeConnectedMajor(args, callbackContext);
		}
		else if ("beacons_writeConnectedMinor".equals(action)) {
			writeConnectedMinor(args, callbackContext);
		}
		else if ("bluetooth_bluetoothState".equals(action)) {
			checkBluetoothState(args, callbackContext);
		}else if ("nearables_startRangingForType".equals(action)) {
			startRangingNearables(args, callbackContext);
		} else {
			return false;
		}
		return true;
	}

	/**
	 * If Bluetooth is off, open a Bluetooth dialog.
	 */
	private void checkBluetoothState(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "checkBluetoothState");

		// Check that no Bluetooth state request is in progress.
		if (null != mBluetoothStateCallbackContext) {
			callbackContext.error("Bluetooth state request already in progress");
			return;
		}

		// Check if Bluetooth is enabled.
		//BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		if (!SystemRequirementsHelper.isBluetoothEnabled(getApplicationContext())) {
			// Open Bluetooth dialog on the UI thread.
			final CordovaPlugin self = this;
			mBluetoothStateCallbackContext = callbackContext;
			Runnable openBluetoothDialog = new Runnable() {
				public void run() {
					Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
					mCordovaInterface.startActivityForResult(
							self,
							enableIntent,
							REQUEST_ENABLE_BLUETOOTH);
				}
			};
			mCordovaInterface.getActivity().runOnUiThread(openBluetoothDialog);
		}
		else {
			// Bluetooth is enabled, return the result to JavaScript,
			sendResultForBluetoothEnabled(callbackContext);
		}
	}

	/**
	 * Check if Bluetooth is enabled and return result to JavaScript.
	 */
	public void sendResultForBluetoothEnabled(CallbackContext callbackContext)
	{
		if (SystemRequirementsHelper.isBluetoothEnabled(getApplicationContext())) {
			callbackContext.success(1);
		}
		else {
			callbackContext.success(0);
		}
	}

	/**
	 * Called when the Bluetooth dialog is closed.
	 */
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent)
	{
		Log.i(LOGTAG, "onActivityResult");
		if (REQUEST_ENABLE_BLUETOOTH == requestCode) {
			sendResultForBluetoothEnabled(mBluetoothStateCallbackContext);
			mBluetoothStateCallbackContext = null;
		}
	}

	/**
	 * Start ranging for beacons.
	 */
	private void startRangingBeaconsInRegion(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "startRangingBeaconsInRegion");

		JSONObject json = cordovaArgs.getJSONObject(0);

		final BeaconRegion region = createRegion(json);

		// TODO: How to handle case when region already ranged?
		// Stop ranging then start again?
		// Currently, if ranging callback already exists we
		// do nothing, just return.
		String key = regionHashMapKey(region);
		if (null != mRangingCallbackContexts.get(key)) {
			return;
		}

		// Add callback to hash map.
		mRangingCallbackContexts.put(key, callbackContext);

		// Create ranging listener.
		mBeaconManager.setRangingListener(new PluginRangingListener());

		// If connected start ranging immediately, otherwise first connect.
		if (mIsConnected) {
			startRanging(region, callbackContext);
		}
		else {
			Log.i(LOGTAG, "connect");

			// todo: consider whether this holds up to several startRanging(...)
			//   calls before onServiceReady() fires
			mBeaconManager.connect(new BeaconManager.ServiceReadyCallback() {
				@Override
				public void onServiceReady() {
					Log.i(LOGTAG, "onServiceReady");
					mIsConnected = true;
					startRanging(region, callbackContext);
				}
			});
		}
	}

	String scanId = "";

	/**
	 * Start ranging for nearables.
	 */
	private void startRangingNearables(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "startRangingNearables");
//		callbackContext.success("Successful");
		//mBeaconManager.
		EstimoteSDK.initialize(cordova.getActivity(), getAppId(), getAppToken());
		try{
			mBeaconManager.setNearableListener(new BeaconManager.NearableListener() {
				@Override
				public void onNearablesDiscovered(List<Nearable> list) {
					Log.i(LOGTAG, "nearablesDiscovered");
					if (list.size() > 0){
						Log.i(LOGTAG, list.get(0).identifier);
						try {
							callbackContext.success(makeJSONNearableArray(list));
						}catch (JSONException e){
							Log.e("JSONException", e.getMessage());
						}
					}
				}
			});

			mBeaconManager.connect(new BeaconManager.ServiceReadyCallback() {
				@Override
				public void onServiceReady() {
					mBeaconManager.startNearableDiscovery();
				}
			});
		}
		catch (Exception e){
			Log.e(LOGTAG, "startRangingNearables error:", e);
			callbackContext.error("Start rangingNearables RemoteException");
		}
	}

	/**
	 * Helper method.
	 */
	private void startRanging(BeaconRegion region, CallbackContext callbackContext)
	{
		//TODO: Implement ranging exception block
		try {
			Log.i(LOGTAG, "startRanging");
			mBeaconManager.startRanging(region);
		}
		catch(Exception e) {
			Log.e(LOGTAG, "startRanging error:", e);
			callbackContext.error("Start ranging RemoteException");
		}
	}

	/**
	 * Stop ranging for beacons.
	 */
	private void stopRangingBeaconsInRegion(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "stopRangingBeaconsInRegion");

		JSONObject json = cordovaArgs.getJSONObject(0);

		BeaconRegion region = createRegion(json);

		// If ranging callback does not exist call error callback
		String key = regionHashMapKey(region);
		CallbackContext rangingCallback = mRangingCallbackContexts.get(key);
		if (null == rangingCallback) {
			callbackContext.error("Region not ranged");
			return;
		}

		// Remove ranging callback from hash map.
		mRangingCallbackContexts.remove(key);

		// Clear ranging callback on JavaScript side.
		PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
		result.setKeepCallback(false);
		rangingCallback.sendPluginResult(result);

		// Stop ranging if connected.
		if (mIsConnected) {
			try {
				Log.i(LOGTAG, "stopRanging");

				// Stop ranging.
				mBeaconManager.stopRanging(region);

				// Send back success.
				callbackContext.success();
			}
			catch(Exception e) {
				Log.e(LOGTAG, "stopRanging", e);
				callbackContext.error("stopRanging RemoteException");
			}
		}
		else {
			callbackContext.error("Not connected");
		}
	}

	/**
	 * Start monitoring for region.
	 */
	private void startMonitoringForRegion(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "startMonitoringForRegion");

		JSONObject json = cordovaArgs.getJSONObject(0);

		final BeaconRegion region = createRegion(json);

		// TODO: How to handle case when region already monitored?
		// Stop monitoring then start again?
		// Currently, if monitoring callback already exists we
		// do nothing, just return.
		String key = regionHashMapKey(region);
		if (null != mMonitoringCallbackContexts.get(key)) {
			Log.i(LOGTAG, "Monitor already active for this region. Re-registering");
			// Remove monitoring callback from hash map.
			mMonitoringCallbackContexts.remove(key);
		}

		// Add callback to hash map.
		mMonitoringCallbackContexts.put(key, callbackContext);

		// Create monitoring listener.
		mBeaconManager.setMonitoringListener(new PluginMonitoringListener());

		// If connected start monitoring immediately, otherwise first connect.
		if (mIsConnected) {
			startMonitoring(region, callbackContext);
		}
		else {
			Log.i(LOGTAG, "connect");
			mBeaconManager.connect(new BeaconManager.ServiceReadyCallback() {
				@Override
				public void onServiceReady() {
					Log.i(LOGTAG, "onServiceReady");
					mIsConnected = true;
					startMonitoring(region, callbackContext);
				}
			});
		}
	}

	/**
	 * Helper method.
	 */
	private void startMonitoring(BeaconRegion region, CallbackContext callbackContext)
	{
		try {
			Log.i(LOGTAG, "startMonitoring");
			mBeaconManager.startMonitoring(region);
		}
		catch(Exception e) {
			Log.e(LOGTAG, "startMonitoring error:", e);
			callbackContext.error("startMonitoring RemoteException");
		}
	}

	/**
	 * Stop monitoring for region.
	 */
	private void stopMonitoringForRegion(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "stopMonitoringForRegion");

		JSONObject json = cordovaArgs.getJSONObject(0);

		BeaconRegion region = createRegion(json);

		// If monitoring callback does not exist call error callback
		String key = regionHashMapKey(region);
		CallbackContext monitoringCallback = mMonitoringCallbackContexts.get(key);
		if (null == monitoringCallback) {
			callbackContext.error("Region not monitored");
			return;
		}

		// Remove monitoring callback from hash map.
		mMonitoringCallbackContexts.remove(key);

		// Clear monitoring callback on JavaScript side.
		PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
		result.setKeepCallback(false);
		monitoringCallback.sendPluginResult(result);

		// Stop monitoring if connected.
		if (mIsConnected) {
			try {
				Log.i(LOGTAG, "stopMonitoring");

				// Stop monitoring.
				mBeaconManager.stopMonitoring(region.getIdentifier());
				//mBeaconManager.stopMonitoring(region);

				// Send back success.
				callbackContext.success();
			}
			catch(Exception e) {
				Log.e(LOGTAG, "stopMonitoring", e);
				callbackContext.error("stopMonitoring RemoteException");
			}
		}
		else {
			callbackContext.error("Not connected");
		}
	}

	/**
	 * Authenticate with Estimote Cloud
	 */
	private void setupAppIDAndAppToken(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "setupAppIDAndAppToken");

		if (mEstimoteSDK == null) {
			mEstimoteSDK = new EstimoteSDK();

			String appID = cordovaArgs.getString(0);
			String appToken = cordovaArgs.getString(1);
			EstimoteSDK.initialize(cordova.getActivity(), appID, appToken);
			PluginResult r = new PluginResult(PluginResult.Status.OK);
			callbackContext.sendPluginResult(r);
		} else {
			// todo consider including helpful info e.g. appID
			callbackContext.error("already authenticated to Estimote Cloud");
		}
	}

	/**
	 * Find beacon in rangedBeacons, with MAC address
	 */
	private Beacon findBeacon(String macAddress) {
		Log.i(LOGTAG, "findBeacon(String)");
		for (Iterator<Beacon> i = mRangedBeacons.iterator(); i.hasNext();) {
			Beacon beacon = i.next();
			if (beacon.getMacAddress().equals(macAddress)) {
				return beacon;
			}
		}

		return null;
	}

	/**
	 * Find beacon in rangedBeacons, with region params
	 */
	private Beacon findBeacon(String proximityUUID, int major, int minor) {
		Log.i(LOGTAG, "findBeacon(String, int, int)");
		for (Iterator<Beacon> i = mRangedBeacons.iterator(); i.hasNext();) {
			Beacon beacon = i.next();
			if (beacon.getProximityUUID().equals(proximityUUID) &&
					beacon.getMajor() == major &&
					beacon.getMinor() == minor) {
				return beacon;
			}
		}

		return null;
	}

	/**
	 * Find beacon in rangedBeacons, from JSON
	 */
	private Beacon findBeacon(JSONObject json) throws JSONException {
		String macAddress = json.optString("macAddress", "");

		if (!macAddress.equals("")) {
			return findBeacon(macAddress);
		} else {
			String proximityUUID = json.optString("proximityUUID", "");
			int major = json.optInt("major", -1);
			int minor = json.optInt("minor", -1);

			if (!proximityUUID.equals("") && major > -1 && minor > -1) {
				return findBeacon(proximityUUID, major, minor);
			}
		}

		return null;
	}

	// todo: consider mac address only version?
	/**
	 * Connect to beacon
	 */
	private void connectToBeacon(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "connectToBeacon");

		JSONObject json = cordovaArgs.getJSONObject(0);

		Beacon beacon = findBeacon(json);
		if (beacon == null) {
			callbackContext.error("could not find beacon");
			return;
		}

		// beacons are jealous creatures and don't like competition
		if (mConnectedBeacon != null &&
				!mConnectedBeacon.getMacAddress().equals(beacon.getMacAddress())) {
			disconnectConnectedBeacon();
		}

		mBeaconConnectionCallback = callbackContext;
		mConnectedBeacon = new BeaconConnected(
				cordova.getActivity(),
				beacon,
				new PluginConnectingListener()
		);

		mConnectedBeacon.authenticate();

		return;
	}

	/**
	 * Disconnect connected beacon
	 */
	private void disconnectConnectedBeacon() {
		Log.i(LOGTAG, "disconnectConnectedBeacon");

		if (mConnectedBeacon != null && mConnectedBeacon.isConnected()) {
			mConnectedBeacon.close();
			mConnectedBeacon = null;
		}
	}

	/**
	 * Disconnect connected beacon, c/o Cordova
	 */
	private void disconnectConnectedBeacon(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "disconnectConnectedBeacon (cordova)");

		mBeaconDisconnectionCallback = callbackContext;
		disconnectConnectedBeacon();
	}

	/**
	 * Write Proximity UUID to connected beacon
	 */
	private void writeConnectedProximityUUID(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "writeConnectedProximityUUID");

		if (mConnectedBeacon != null && mConnectedBeacon.isConnected()) {
			String uuid = cordovaArgs.getString(0);

			Log.i(LOGTAG, uuid);
			Log.i(LOGTAG, mConnectedBeacon.getBeacon().getProximityUUID().toString());
			Log.i(LOGTAG, String.valueOf(uuid.equals(mConnectedBeacon.getBeacon().getProximityUUID())));

			// already correct, skip
			if (uuid.equals(mConnectedBeacon.getBeacon().getProximityUUID())) {
				PluginResult r = new PluginResult(PluginResult.Status.OK);
				callbackContext.sendPluginResult(r);
			}

			try {
				UUID.fromString(uuid);
			} catch (Exception e) {
				callbackContext.error(e.getMessage());
			}

			BeaconConnection.WriteCallback writeCallback;
			writeCallback = new BeaconConnection.WriteCallback() {
				@Override
				public void onSuccess() {
					PluginResult r = new PluginResult(PluginResult.Status.OK);
					callbackContext.sendPluginResult(r);
				}

				@Override
				public void onError(EstimoteDeviceException e) {
					callbackContext.error(e.getMessage());
				}
			};
			//TODO: Implement writeProximityUUID Method.
			//mConnectedBeacon.writeProximityUuid(uuid, writeCallback);
		}
	}

	/**
	 * Write Major to connected beacon
	 */
	private void writeConnectedMajor(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "writeConnectedMajor");

		if (mConnectedBeacon != null && mConnectedBeacon.isConnected()) {
			int major = cordovaArgs.getInt(0);

			// already correct, skip
			if (major == mConnectedBeacon.getBeacon().getMajor()) {
				PluginResult r = new PluginResult(PluginResult.Status.OK);
				callbackContext.sendPluginResult(r);
			}

			if (major == 0) {
				callbackContext.error("major cannot be 0");
				return;
			}

			BeaconConnection.WriteCallback writeCallback;
			writeCallback = new BeaconConnection.WriteCallback() {
				@Override
				public void onSuccess() {
					PluginResult r = new PluginResult(PluginResult.Status.OK);
					callbackContext.sendPluginResult(r);
				}

				@Override
				public void onError(EstimoteDeviceException e) {
					callbackContext.error(e.getMessage());
				}
			};
			//TODO: Impelement writeMajor function.
			//mConnectedBeacon.writeMajor(major, writeCallback);
		}
	}

	/**
	 * Write Minor to connected beacon
	 */
	private void writeConnectedMinor(
			CordovaArgs cordovaArgs,
			final CallbackContext callbackContext)
			throws JSONException
	{
		Log.i(LOGTAG, "writeConnectedMinor");

		if (mConnectedBeacon != null && mConnectedBeacon.isConnected()) {
			int minor = cordovaArgs.getInt(0);

			// already correct, skip
			if (minor == mConnectedBeacon.getBeacon().getMinor()) {
				PluginResult r = new PluginResult(PluginResult.Status.OK);
				callbackContext.sendPluginResult(r);
			}

			if (minor == 0) {
				callbackContext.error("minor cannot be 0");
				return;
			}

			BeaconConnection.WriteCallback writeCallback;
			writeCallback = new BeaconConnection.WriteCallback() {
				@Override
				public void onSuccess() {
					PluginResult r = new PluginResult(PluginResult.Status.OK);
					callbackContext.sendPluginResult(r);
				}

				@Override
				public void onError(EstimoteDeviceException e) {
					callbackContext.error(e.getMessage());
				}
			};
			//TODO: Implement writeMinor function.
			//mConnectedBeacon.writeMinor(minor, writeCallback);
		}
	}

	/**
	 * Create JSON object representing beacon info.
	 *
	 * beaconInfo format:
	 * {
	 *	 region: region,
	 *	 beacons: array of beacon
	 * }
	 */
	private JSONObject makeJSONBeaconInfo(BeaconRegion region, List<Beacon> beacons)
			throws JSONException
	{
		// Create JSON object.
		JSONObject json = new JSONObject();
		json.put("region", makeJSONRegion(region));
		json.put("beacons", makeJSONBeaconArray(beacons));
		return json;
	}

	/**
	 * Create JSON object representing a region.
	 */
	private static JSONObject makeJSONRegion(BeaconRegion region)
			throws JSONException
	{
		return makeJSONRegion(region, null);
	}

	/**
	 * Create JSON object representing a region in the given state.
	 */
	private static JSONObject makeJSONRegion(BeaconRegion region, String state)
			throws JSONException
	{
		JSONObject json = new JSONObject();
		json.put("identifier", region.getIdentifier());
		json.put("uuid", region.getProximityUUID());
		json.put("major", region.getMajor());
		json.put("minor", region.getMinor());
		if (state != null) {
			json.put("state", state);
		}
		return json;
	}

	/**
	 * Create JSON object representing a beacon list.
	 */
	private JSONArray makeJSONBeaconArray(List<Beacon> beacons)
			throws JSONException
	{
		final JSONArray jsonArray = new JSONArray();
		for (final Beacon b : beacons) {
			// Compute proximity value.
			Proximity proximityValue = computeProximity(b);
			int proximity = 0; // Unknown.
			if (Proximity.IMMEDIATE == proximityValue) { proximity = 1; }
			else if (Proximity.NEAR == proximityValue) { proximity = 2; }
			else if (Proximity.FAR == proximityValue) { proximity = 3; }
			final int proximity_ = proximity;
			// Compute distance value.
			final double distance = computeAccuracy(b);

			// Normalize UUID.

			String uuid = (b.getProximityUUID().toString());
			EstimoteCloud estimoteCloud = EstimoteCloud.getInstance();
			estimoteCloud.fetchBeaconDetails(b.getMacAddress(), new CloudCallback<BeaconInfo>() {
				@Override
				public void success(BeaconInfo beaconInfo) {
					// Construct JSON object for beacon.
					try {
						JSONObject json = new JSONObject();
						json.put("major", beaconInfo.major);
						json.put("minor", beaconInfo.minor);
						json.put("rssi", b.getRssi());
						json.put("measuredPower", beaconInfo.settings.broadcastingPower.powerInDbm);
						json.put("proximityUUID", beaconInfo.uuid);
						json.put("proximity", proximity_);
						json.put("distance", distance);
						json.put("name", beaconInfo.name);
						json.put("macAddress", beaconInfo.macAddress);
						jsonArray.put(json);
					}
					catch (JSONException e){
						Log.e("JSONException", e.getMessage());
					}
				}

				@Override
				public void failure(EstimoteCloudException e) {

				}
			});

		}
		return jsonArray;
	}

	private JSONArray makeJSONNearableArray(List<Nearable> nearables)
		throws JSONException{
		final JSONArray jsonArray = new JSONArray();


		for (final Nearable nearable : nearables){
			try{
				JSONObject jsonObject = new JSONObject();
				jsonObject.put("color", nearable.color.text);
				jsonObject.put("identifier", nearable.identifier);
				jsonObject.put("type", nearable.type.text);
				jsonObject.put("firmware", nearable.firmwareVersion);
				jsonArray.put(jsonObject);
			} catch (JSONException e){
				Log.e("JSONException", e.getMessage());
			}
		}

		return jsonArray;
	}

	private String regionHashMapKey(String uuid, Integer major, Integer minor) {
		if (uuid == null) {
			uuid = "0";
		}

		if (major == null) {
			major = 0;
		}

		if (minor == null) {
			minor = 0;
		}

		// use % for easier decomposition
		return uuid + "%" + major + "%" + minor;
	}

	private String regionHashMapKey(BeaconRegion region)
	{
		String uuid = region.getProximityUUID().toString();
		Integer major = region.getMajor();
		Integer minor = region.getMinor();

		return regionHashMapKey(uuid, major, minor);
	}

	/**
	 * Create a Region object from Cordova arguments.
	 */
	private BeaconRegion createRegion(JSONObject json) {
		// null ranges all regions, if unset
		String uuid = json.optString("uuid", null);
		Integer major = optUInt16Null(json, "major");
		Integer minor = optUInt16Null(json, "minor");

		String identifier = json.optString(
				"identifier",
				regionHashMapKey(uuid, major, minor)
		);

		return new BeaconRegion(identifier, java.util.UUID.fromString(uuid), major, minor);
	}

	/**
	 * Create a Region object from HashMap key.
	 */
	private BeaconRegion createRegion(String key) {
		String[] regionValues = key.split("%");
		String uuid = regionValues[0];
		int major = Integer.parseInt(regionValues[1]);
		int minor = Integer.parseInt(regionValues[2]);

		return new BeaconRegion(key, java.util.UUID.fromString(uuid), major, minor);
	}


	/**
	 * Returns the value mapped by name if it exists and is a positive integer
	 * no larger than 0xFFFF.
	 * Returns null otherwise.
	 */
	private Integer optUInt16Null(JSONObject json, String name) {
		int i = json.optInt(name, -1);
		if (i < 0 || i > (0xFFFF)) {
			return null;
		}
		else {
			return new Integer(i);
		}
	}

	/**
	 * Listener for ranging events.
	 */
	class PluginRangingListener implements BeaconManager.BeaconRangingListener {
		@Override
		public void onBeaconsDiscovered(BeaconRegion region, List<Beacon> beacons) {
			Log.i(LOGTAG, "onBeaconsDiscovered");

			try {
				// store in plugin
				mRangedBeacons.clear();
				mRangedBeacons.addAll(beacons);

				// Find region callback.
				String key = regionHashMapKey(region);
				CallbackContext rangingCallback = mRangingCallbackContexts.get(key);
				if (null == rangingCallback) {
					// No callback found.
					Log.e(LOGTAG,
							"onBeaconsDiscovered no callback found for key: " + key);
					return;
				}

				// Create JSON beacon info object.
				JSONObject json = makeJSONBeaconInfo(region, beacons);

				// Send result to JavaScript.
				PluginResult r = new PluginResult(PluginResult.Status.OK, json);
				r.setKeepCallback(true);
				rangingCallback.sendPluginResult(r);
			}
			catch(JSONException e) {
				Log.e(LOGTAG, "onBeaconsDiscovered error:", e);
			}
		}
	}

	/**
	 * Listener for monitoring events.
	 */
	class PluginMonitoringListener implements BeaconManager.BeaconMonitoringListener {
		private void sendRegionInfo(BeaconRegion region, String state) {
			try {
				// Find region callback.
				String key = regionHashMapKey(region);
				CallbackContext monitoringCallback = mMonitoringCallbackContexts.get(key);
				if (null == monitoringCallback) {
					// No callback found.
					Log.e(LOGTAG, "sendRegionInfo no callback found for key: " + key);
					return;
				}

				// Create JSON region info object with the given state.
				JSONObject json = makeJSONRegion(region, state);

				// Send result to JavaScript.
				PluginResult r = new PluginResult(PluginResult.Status.OK, json);
				r.setKeepCallback(true);
				monitoringCallback.sendPluginResult(r);
			}
			catch(JSONException e) {
				Log.e(LOGTAG, "sendRegionInfo error:", e);
			}
		}

		@Override
		public void onEnteredRegion(BeaconRegion region, List<Beacon> list) {
			// Note that results are not delivered on UI thread.

			Log.i(LOGTAG, "onEnteredRegion");

			sendRegionInfo(region, "inside");
		}

		@Override
		public void onExitedRegion(BeaconRegion region) {
			// Note that results are not delivered on UI thread.
			Log.i(LOGTAG, "onExitedRegion");

			sendRegionInfo(region, "outside");
		}
	}

	/**
	 * Listener for beacon connection events
	 */
	class PluginConnectingListener implements BeaconConnection.ConnectionCallback
	{
		@Override
		public void onAuthorized(BeaconInfo beaconInfo) {
			CallbackContext callback = mBeaconConnectionCallback;

			if (callback == null) {
				return;
			}

			try {
				JSONObject json = new JSONObject();

				// add beaconInfo
				json.put(
						"batteryLifeExpectancyInDays",
						beaconInfo.batteryLifeExpectancyInDays
				);
				json.put("color", beaconInfo.color.toString());
				json.put("macAddress", beaconInfo.macAddress);
				json.put("major", beaconInfo.major);
				json.put("minor", beaconInfo.minor);
				json.put("name", beaconInfo.name);
				json.put("uuid", beaconInfo.uuid);

				Log.i(LOGTAG, "2");
				// add beaconInfo.settings
				BeaconInfoSettings settings = beaconInfo.settings;
				JSONObject jsonSettings = new JSONObject();
				jsonSettings.put(
						"advertisingIntervalMillis",
						settings.advertisingIntervalMillis
				);
				jsonSettings.put("batteryLevel", settings.batteryLevel);
				jsonSettings.put(
						"broadcastingPower",
						settings.broadcastingPower
				);
				jsonSettings.put("firmware", settings.firmware);
				jsonSettings.put("hardware", settings.hardware);

				Log.i(LOGTAG, "3");
				// finish up response param
				json.put("settings", jsonSettings);

				Log.i(LOGTAG, "4");
				Log.i(LOGTAG, json.toString());
				// pass back to web
				PluginResult r = new PluginResult(PluginResult.Status.OK, json);
				callback.sendPluginResult(r);
			} catch (JSONException e) {
				Log.i(LOGTAG, "inError");
				String msg;
				msg = "connection succeeded, could not marshall object: ";
				msg = msg.concat(e.getMessage());

				callback.error(msg);
			}

			// cleanup
			mBeaconConnectionCallback = null;
		}

		@Override
		public void onConnected(BeaconInfo beaconInfo) {
			//TODO: Implement onConnected method.
		}

		@Override public void onAuthenticationError(EstimoteDeviceException e) {
			CallbackContext callback = mBeaconConnectionCallback;

			if (callback == null) {
				return;
			}

			// pass back to js
			callback.error(e.getMessage());

			// print stacktrace to android logs
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			Log.e(LOGTAG, sw.toString());

			// cleanup
			mBeaconConnectionCallback = null;
		}

		@Override public void onDisconnected() {
			CallbackContext callback = mBeaconDisconnectionCallback;

			if (callback == null) {
				return;
			}

			PluginResult r = new PluginResult(PluginResult.Status.OK);
			callback.sendPluginResult(r);

			// cleanup
			mBeaconDisconnectionCallback = null;
		}
	}

	public class BeaconConnected extends BeaconConnection {
		private Beacon mBeacon;

		public BeaconConnected(
				Context context,
				Beacon beacon,
				BeaconConnection.ConnectionCallback connectionCallback
		) {
			super(context, beacon, connectionCallback);
			this.mBeacon = beacon;
		}

		public Beacon getBeacon() {
			return mBeacon;
		}
	}
}
