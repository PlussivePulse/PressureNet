package ca.cumulonimbus.barometernetwork;

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.HashMap;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.widget.Toast;
import ca.cumulonimbus.pressurenetsdk.CbCurrentCondition;
import ca.cumulonimbus.pressurenetsdk.CbForecastAlert;
import ca.cumulonimbus.pressurenetsdk.CbScience;
import ca.cumulonimbus.pressurenetsdk.CbService;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.mixpanel.android.mpmetrics.MixpanelAPI;

public class NotificationSender extends BroadcastReceiver {

	Context mContext;
	public static final int PRESSURE_NOTIFICATION_ID  = 101325;
	public static final int CONDITION_NOTIFICATION_ID = 100012;
	public static final int ALERT_NOTIFICATION_ID = 100013;
	
	private long lastNearbyConditionReportNotification = System.currentTimeMillis() 
			- (1000 * 60 * 60);
	private long lastConditionsSubmit = System.currentTimeMillis() 
			- (1000 * 60 * 60 * 4);
	
	Handler notificationHandler = new Handler();
	
	MixpanelAPI mixpanel;
	

	/**
	 * Enum used to identify the tracker that needs to be used for tracking.
	 *
	 * A single tracker is usually enough for most purposes. In case you do need multiple trackers,
	 * storing them all in Application object helps ensure that they are created only once per
	 * application instance.
	 */
	public enum TrackerName {
	  APP_TRACKER, // Tracker used only in this app.
	  GLOBAL_TRACKER, // Tracker used by all the apps from a company. eg: roll-up tracking.

	}

	HashMap<TrackerName, Tracker> mTrackers = new HashMap<TrackerName, Tracker>();
	
	synchronized Tracker getTracker(TrackerName trackerId) {
		  if (!mTrackers.containsKey(trackerId)) {

		    GoogleAnalytics analytics = GoogleAnalytics.getInstance(mContext);
		    Tracker t = (trackerId == TrackerName.APP_TRACKER) ? analytics.newTracker(PressureNETConfiguration.GA_TOKEN)
		        : (trackerId == TrackerName.GLOBAL_TRACKER) ? analytics.newTracker(R.xml.global_tracker)
		            : analytics.newTracker(R.xml.global_tracker);
		    mTrackers.put(trackerId, t);

		  }
		  return mTrackers.get(trackerId);
		}
	
	public NotificationSender() {
		super();
	}
	
	public class NotificationCanceler implements Runnable {

		Context cancelContext;
		int id;
		
		public NotificationCanceler (Context context, int notID) {
			cancelContext = context;
			id = notID;
		}
		
		@Override
		public void run() {
			 if (cancelContext!=null) {
				 String ns = Context.NOTIFICATION_SERVICE;
				 NotificationManager nMgr = (NotificationManager) cancelContext.getSystemService(ns);
				 nMgr.cancel(id);
			 }
		}
		
	}
	
	@Override
	public void onReceive(Context context, Intent intent) {
		mContext = context;
		mixpanel = MixpanelAPI.getInstance(context, PressureNETConfiguration.MIXPANEL_TOKEN);
		if(intent.getAction().equals(CbService.LOCAL_CONDITIONS_ALERT)) {
			log("app received intent local conditions alert");
			// potentially notify about nearby conditions
			SharedPreferences sharedPreferences = PreferenceManager
					.getDefaultSharedPreferences(mContext);
			boolean isOkayToDeliver = sharedPreferences.getBoolean("send_condition_notifications", false);
			if(isOkayToDeliver) {
				try { 
					if(intent.hasExtra("ca.cumulonimbus.pressurenetsdk.conditionNotification")) {
						CbCurrentCondition receivedCondition = (CbCurrentCondition) intent.getSerializableExtra("ca.cumulonimbus.pressurenetsdk.conditionNotification");
						if(receivedCondition != null) {
							if(receivedCondition.getLocation()==null) {
								Location loc = new Location("network");
								loc.setLatitude(receivedCondition.getLat());
								loc.setLongitude(receivedCondition.getLon());
								receivedCondition.setLocation(loc);
							}

							deliverConditionNotification(receivedCondition);
							
						}
					} else {
						log("local conditions intent not sent, doesn't have extra");
					}
				} catch(RuntimeException re) {
					log("notificationsender runtime exception " + re.getMessage());
				}
			} else {
				log("not delivering conditions notification, disabled in prefs");
			}
			
		} else if(intent.getAction().equals(CbService.WEATHER_FORECAST_ALERT)) {
			log("app received intent weather forecast alert");
			// potentially notify about weather forecasts
			SharedPreferences sharedPreferences = PreferenceManager
					.getDefaultSharedPreferences(mContext);

				CbForecastAlert alert = (CbForecastAlert) intent.getSerializableExtra("ca.cumulonimbus.pressurenetsdk.alertNotification");
				CbCurrentCondition condition = (CbCurrentCondition) intent.getSerializableExtra("ca.cumulonimbus.pressurenetsdk.alertNotificationCondition");
				if(alert != null) {

					if(condition != null) {
						log("notificationsender alert condition is good, assigning a location");
						Location loc = new Location("network");
						LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
						Location location = lm
								.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
						loc.setLatitude(condition.getLat());
						loc.setLongitude(condition.getLon());
						condition.setLocation(loc);
						alert.setCondition(condition);

						Calendar cal = Calendar.getInstance();
						condition.setTzoffset(cal.getTimeZone().getRawOffset());


						alert.setCondition(condition);

						log("delivering weather forecast alert");
						boolean isOkayToDeliver = sharedPreferences.getBoolean("send_condition_notifications", false);
						if(isOkayToDeliver) {
							log("notification sender delivering forecast alert, permission granted");
							deliverAlertNotification(alert);
						} else {
							log("notification sender NOT delivering forecast alert, permission denied");
						}
					} else {
						log("notificationsender not sending alert, condition is null");
					}
				}
			
		} else if(intent.getAction().equals(CbService.PRESSURE_CHANGE_ALERT)) {
			log("app received intent pressure change alert");
			if(intent.hasExtra("ca.cumulonimbus.pressurenetsdk.tendencyChange")) {
				String tendencyChange = intent.getStringExtra("ca.cumulonimbus.pressurenetsdk.tendencyChange");
				deliverNotification(tendencyChange);
				
			} else {
				log("pressure change intent not sent, doesn't have extra");
			}
			
		} else if(intent.getAction().equals(CbService.PRESSURE_SENT_TOAST)) {
			log("app received intent pressure sent toast");
			if(intent.hasExtra("ca.cumulonimbus.pressurenetsdk.pressureSent")) {
				double pressureSent = intent.getDoubleExtra("ca.cumulonimbus.pressurenetsdk.pressureSent", 0.0);
				// Toast.makeText(context, "Sent " + displayPressureValue(pressureSent), Toast.LENGTH_SHORT).show();
			} else {
				log("pressure sent intent not sent, doesn't have extra");
			}
			
		} else if(intent.getAction().equals(CbService.CONDITION_SENT_TOAST)) {
			log("app received intent pressure sent toast");
			if(intent.hasExtra("ca.cumulonimbus.pressurenetsdk.conditionSent")) {
				String conditionSent = intent.getStringExtra("ca.cumulonimbus.pressurenetsdk.conditionSent");
				Toast.makeText(context, "Sent " + conditionSent, Toast.LENGTH_SHORT).show();
			} else {
				log("condition sent intent not sent, doesn't have extra");
			}
			
		} else if(intent.getAction().equals("ca.cumulonimbus.barometernetwork.CANCEL_CONDITION")) {
			log("app notificationsender receiving condition cancel");
			NotificationCanceler cancel = new NotificationCanceler(mContext, CONDITION_NOTIFICATION_ID);
			notificationHandler.post(cancel);
						
    	} else if(intent.getAction().equals("ca.cumulonimbus.barometernetwork.CANCEL_PRESSURE")) {
    		log("app notificationsender receiving pressure cancel");
			NotificationCanceler cancel = new NotificationCanceler(mContext, PRESSURE_NOTIFICATION_ID);
			notificationHandler.post(cancel);
    					
    	} else if(intent.getAction().equals("ca.cumulonimbus.barometernetwork.CANCEL_ALERT")) {
			log("app notificationsender receiving alert cancel");
			NotificationCanceler cancel = new NotificationCanceler(mContext, ALERT_NOTIFICATION_ID);
			notificationHandler.post(cancel);
						
    	} 
		
		else {
			log("no matching code for " + intent.getAction());
		}	
	}

	/**
	 * Check the Android SharedPreferences for important values. Save relevant
	 * ones to CbSettings for easy access in submitting readings
	 */
	public String getUnitPreference() {
		SharedPreferences sharedPreferences = PreferenceManager
				.getDefaultSharedPreferences(mContext);
		return sharedPreferences.getString("units", "millibars");
	}
	
	private String displayPressureValue(double value) {
		String preferencePressureUnit = getUnitPreference();
		DecimalFormat df = new DecimalFormat("####.0");
		PressureUnit unit = new PressureUnit(preferencePressureUnit);
		unit.setValue(value);
		unit.setAbbreviation(preferencePressureUnit);
		double pressureInPreferredUnit = unit.convertToPreferredUnit();
		return df.format(pressureInPreferredUnit) + " " + unit.fullToAbbrev();
	}
	
	public boolean wasRecentlyDelivered(CbCurrentCondition condition) {
		PnDb pn = new PnDb(mContext);
		pn.open();
		Cursor recentDeliveries = pn.fetchRecentDeliveries();
		boolean delivered = false;
		while(recentDeliveries.moveToNext()) {
			String general = recentDeliveries.getString(1);
			if(condition.getGeneral_condition().equals(general)) {
				log("notification recently delivered: " + general);
				delivered = true;
			}
		}
		pn.close();
		
		return delivered;
	}
	
	/**
	 * Send an Android notification to the user about 
	 * weather forecast details
	 *
	 */
	private void deliverAlertNotification(CbForecastAlert alert) {
		SharedPreferences sharedPreferences = PreferenceManager
				.getDefaultSharedPreferences(mContext);

		CbCurrentCondition condition = alert.getCondition();
		
		long now = System.currentTimeMillis();
		
		// feed it with the initial condition
		// clear, fog, cloud, precip, thunderstorm
		String initial = "";
		int icon = R.drawable.ic_launcher;
		String politeReportText = condition.getGeneral_condition();
		if(condition.getGeneral_condition().equals(mContext.getString(R.string.sunny))) {
			initial = "clear";
			// pick the right clear icon
			icon = getResIdForClearIcon(condition);
			// vectorString = displayDistance(distance) + " " + CbScience.englishDirection(angle);
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.foggy))) {
			initial = "fog";
			icon = R.drawable.ic_wea_on_fog1;
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.cloudy))) {
			// don't notify on cloudy
			
			initial = "cloud";
			icon = R.drawable.ic_wea_on_cloud;
			
			if(condition.getCloud_type().equals(mContext.getString(R.string.partly_cloudy))) {
				icon = R.drawable.ic_wea_on_cloudy1;
				politeReportText = mContext.getString(R.string.partly_cloudy);
			} else if(condition.getCloud_type().equals(mContext.getString(R.string.mostly_cloudy))) {
				icon = R.drawable.ic_wea_on_cloudy2;
				politeReportText = mContext.getString(R.string.mostly_cloudy);
			} else if(condition.getCloud_type().equals(mContext.getString(R.string.very_cloudy))) {
				icon = R.drawable.ic_wea_on_cloudy;
				politeReportText = mContext.getString(R.string.very_cloudy);
			} else {
				icon = R.drawable.ic_wea_on_cloud;
			}
			
			// vectorString = displayDistance(distance) + " " + CbScience.englishDirection(angle);
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.precipitation))) {
			
			if(condition.getPrecipitation_type().equals(mContext.getString(R.string.rain))) {
				switch((int)condition.getPrecipitation_amount()) {
				case 0:
					icon = R.drawable.ic_wea_on_rain1;
					politeReportText = mContext.getString(R.string.lightRain);
					break;
				case 1:
					icon = R.drawable.ic_wea_on_rain2;
					politeReportText = mContext.getString(R.string.moderateRain);
					break;
				case 2:
					icon = R.drawable.ic_wea_on_rain3;
					politeReportText = mContext.getString(R.string.heavyRain);
					break;
				default:
					icon = R.drawable.ic_wea_on_rain1;
					politeReportText = mContext.getString(R.string.rain);
				}
			} else if (condition.getPrecipitation_type().equals(mContext.getString(R.string.snow))) {
				switch((int)condition.getPrecipitation_amount()) {
				case 0:
					icon = R.drawable.ic_wea_on_snow1;
					politeReportText = mContext.getString(R.string.lightSnow);
					break;
				case 1:
					icon = R.drawable.ic_wea_on_snow2;
					politeReportText = mContext.getString(R.string.moderateSnow);
					break;
				case 2:
					icon = R.drawable.ic_wea_on_snow3;
					politeReportText = mContext.getString(R.string.heavySnow);
					break;
				default:
					icon = R.drawable.ic_wea_on_snow1;
					politeReportText = mContext.getString(R.string.snow);
				}
			} else if (condition.getPrecipitation_type().equals(mContext.getString(R.string.hail))) {
				switch((int)condition.getPrecipitation_amount()) {
				case 0:
					icon = R.drawable.ic_wea_on_hail1;
					politeReportText = mContext.getString(R.string.lightHail);
					break;
				case 1:
					icon = R.drawable.ic_wea_on_hail2;
					politeReportText = mContext.getString(R.string.moderateHail);
					break;
				case 2:
					icon = R.drawable.ic_wea_on_hail3;;
					politeReportText = mContext.getString(R.string.heavyHail);
					break;
				default:
					icon = R.drawable.ic_wea_on_hail1;
					politeReportText = mContext.getString(R.string.hail);
				}
			} else {
				icon = R.drawable.ic_wea_on_precip;
			}
			
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.thunderstorm))) {
			initial = "thunderstorm";
			icon = R.drawable.ic_wea_on_lightning2;
			
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.extreme))) {
			initial = "severe";
			icon = R.drawable.ic_wea_on_severe;
			
			if(condition.getUser_comment().equals(mContext.getString(R.string.flooding))) {
				icon = R.drawable.ic_wea_on_flooding;
				politeReportText = mContext.getString(R.string.flooding);
			} else if(condition.getUser_comment().equals(mContext.getString(R.string.wildfire))) {
				icon = R.drawable.ic_wea_on_fire;
				politeReportText = mContext.getString(R.string.wildfire);
			} else if(condition.getUser_comment().equals(mContext.getString(R.string.tornado))) {
				icon = R.drawable.ic_wea_on_tornado;
				politeReportText = mContext.getString(R.string.tornado);
			} else if(condition.getUser_comment().equals(mContext.getString(R.string.duststorm))) {
				icon = R.drawable.ic_wea_on_dust;
				politeReportText = mContext.getString(R.string.duststorm);
			} else if(condition.getUser_comment().equals(mContext.getString(R.string.tropicalstorm))) {
				icon = R.drawable.ic_wea_on_tropical_storm;
				politeReportText = mContext.getString(R.string.tropicalstorm);
			}
		} 
		alert.composeNotificationText();



		// Current Conditions activity likes to know the location in the Intent
		// Also needed for Haversine calculation
		double notificationLatitude = 0.0;
		double notificationLongitude = 0.0;
		try {
			LocationManager lm = (LocationManager) mContext
					.getSystemService(Context.LOCATION_SERVICE);
			Location loc = lm
					.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
			if (loc.getLatitude() != 0) {
				notificationLatitude = loc.getLatitude();
				notificationLongitude = loc.getLongitude();
			}
		} catch (Exception e) {

		}


		Notification.Builder mBuilder = new Notification.Builder(
				mContext).setSmallIcon(icon)
				.setContentTitle(alert.getNotificationTitle()).setContentText(alert.getNotificationContent());
		// Creates an explicit intent for an activity
		Intent resultIntent = new Intent(mContext,
				CurrentConditionsActivity.class);


		resultIntent.putExtra("latitude", notificationLatitude);
		resultIntent.putExtra("longitude", notificationLongitude);
		resultIntent.putExtra("cancelNotification", true);
		resultIntent.putExtra("initial", initial);
		resultIntent.putExtra("backToApp", true);

		try {
		
			android.support.v4.app.TaskStackBuilder stackBuilder = android.support.v4.app.TaskStackBuilder
					.create(mContext);
	
			stackBuilder.addNextIntent(resultIntent);
			PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
					PendingIntent.FLAG_UPDATE_CURRENT);
			mBuilder.setContentIntent(resultPendingIntent);
			NotificationManager mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
			// mId allows you to update the
			// notification later on.
			mNotificationManager.notify(ALERT_NOTIFICATION_ID, mBuilder.build());
	
			AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
			Intent i = new Intent("ca.cumulonimbus.barometernetwork.CANCEL_ALERT");
			PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i, 0);
			am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + (1000 * 60 * 60 * 1), pi);
					
			
			// Get tracker.
			Tracker t = getTracker(
			    TrackerName.APP_TRACKER);
			// Build and send an Event.
			t.send(new HitBuilders.EventBuilder()
			    .setCategory(BarometerNetworkActivity.GA_CATEGORY_NOTIFICATIONS)
			    .setAction("forecast_notification_delivered")
			    .setLabel(condition.getGeneral_condition())
			    .build());
			
			try {
				JSONObject props = new JSONObject();
				props.put("Condition", condition.getGeneral_condition());
				mixpanel.track("Forecast Alert Delivered", props);	
			} catch (JSONException jsone) {
				log("forecast notification json exception " + jsone.getMessage());
			}
			
			mixpanel.flush();
			
			// save the time
			SharedPreferences.Editor editor = sharedPreferences.edit();
			editor.putLong("lastConditionTime", now);
			editor.commit();
		} catch(NoSuchMethodError nsme) {
			// 
		}
		
		// Get tracker.
		Tracker t = getTracker(
		    TrackerName.APP_TRACKER);
		// Build and send an Event.
		t.send(new HitBuilders.EventBuilder()
		    .setCategory(BarometerNetworkActivity.GA_CATEGORY_MAIN_APP)
		    .setAction("conditions_notification_delivered_final")
		    .setLabel(condition.getGeneral_condition())
		    .build());
	}
	
	/**
	 * Send an Android notification to the user about nearby users
	 * reporting current conditions.
	 *
	 */
	private void deliverConditionNotification(CbCurrentCondition condition) {
		SharedPreferences sharedPreferences = PreferenceManager
				.getDefaultSharedPreferences(mContext);

		long now = System.currentTimeMillis();
		// don't deliver if recently interacted with
		lastConditionsSubmit = sharedPreferences.getLong(
				"lastConditionsSubmit", System.currentTimeMillis()
				- (1000 * 60 * 60 * 12));
		
		String prefTimeWait = sharedPreferences.getString("condition_refresh_frequency", "3 hours");
		
		boolean alsoAlertClearCloudy = sharedPreferences.getBoolean("also_alert_clear_cloudy", false);
		
		lastNearbyConditionReportNotification = sharedPreferences.getLong(
				"lastConditionTime", System.currentTimeMillis()
						- (1000 * 60 * 60 * 12));
		
		long waitDiff = CbService.stringTimeToLongHack(prefTimeWait);
		
		boolean isCriticalToDeliver = (condition.getGeneral_condition().equals(mContext.getString(R.string.extreme))) || 
										(condition.getGeneral_condition().equals(mContext.getString(R.string.thunderstorm)));
		
		if(!isCriticalToDeliver) {
			if(now - lastConditionsSubmit < waitDiff) {
				log("bailing on conditions notifications, recently submitted one");
				return;
			}
			if (now - lastNearbyConditionReportNotification < waitDiff) {
				log("bailing on conditions notification, not 1h wait yet");
				return;
			}
		}
		
		if(wasRecentlyDelivered(condition)) {
			return;
		}
		
	
		if(condition.getLocation()!=null) {
			PnDb pn = new PnDb(mContext);
			pn.open();
			pn.addDelivery(condition.getGeneral_condition(), condition.getLocation().getLatitude(), condition.getLocation().getLongitude(), condition.getTime());
			pn.close();
		} else {
			log("condition out for notification has no location, bailing");
			return;
		}
		
		String deliveryMessage = mContext.getString(R.string.conditionPrompt);
		
		// Current Conditions activity likes to know the location in the Intent
		// Also needed for Haversine calculation
		double notificationLatitude = 0.0;
		double notificationLongitude = 0.0;
		try {
			LocationManager lm = (LocationManager) mContext
					.getSystemService(Context.LOCATION_SERVICE);
			Location loc = lm
					.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
			if (loc.getLatitude() != 0) {
				notificationLatitude = loc.getLatitude();
				notificationLongitude = loc.getLongitude();
			}
		} catch (Exception e) {

		}
		
		log("haversine inputs: " + notificationLatitude + " " + notificationLongitude + " " + condition.getLat() + " " + condition.getLon());
		double distance = CbScience.haversine(notificationLatitude, notificationLongitude, condition.getLat(), condition.getLon());
		
		if(distance > 20000) {
			log("conditiion notification bailing, distance too large, " + distance + "m away");
			return;
		}
		
		double angle = CbScience.angle(notificationLatitude, notificationLongitude, condition.getLat(), condition.getLon());
		// double angle = CbScience.angleEstimate(condition.getLat(), condition.getLon(), notificationLatitude, notificationLongitude);
		log("notification location " + distance + " " + angle);
		
		String vectorString = "nearby";
		
		// feed it with the initial condition
		// clear, fog, cloud, precip, thunderstorm
		String initial = "";
		int icon = R.drawable.ic_launcher;
		String politeReportText = condition.getGeneral_condition();
		if(condition.getGeneral_condition().equals(mContext.getString(R.string.sunny))) {
			// don't notify on clear
			if(!alsoAlertClearCloudy) {
				log("notification sender not sending, condition is clear and pref not set to deliver");
				return;
			} else {
				log("notification sender: condition is clear and pref set to deliver");				
			}
			
			initial = "clear";
			// pick the right clear icon
			icon = getResIdForClearIcon(condition);
			// vectorString = displayDistance(distance) + " " + CbScience.englishDirection(angle);
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.foggy))) {
			initial = "fog";
			icon = R.drawable.ic_wea_on_fog1;
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.cloudy))) {
			// don't notify on cloudy
			if(!alsoAlertClearCloudy) {
				log("notification sender not sending, condition is cloudy and pref not set to deliver");
				return;
			} else {
				log("notification sender: condition is cloudy and pref set to deliver");				
			}
			
			initial = "cloud";
			icon = R.drawable.ic_wea_on_cloud;
			
			if(condition.getCloud_type().equals(mContext.getString(R.string.partly_cloudy))) {
				icon = R.drawable.ic_wea_on_cloudy1;
				politeReportText = mContext.getString(R.string.partly_cloudy);
			} else if(condition.getCloud_type().equals(mContext.getString(R.string.mostly_cloudy))) {
				icon = R.drawable.ic_wea_on_cloudy2;
				politeReportText = mContext.getString(R.string.mostly_cloudy);
			} else if(condition.getCloud_type().equals(mContext.getString(R.string.very_cloudy))) {
				icon = R.drawable.ic_wea_on_cloudy;
				politeReportText = mContext.getString(R.string.very_cloudy);
			} else {
				icon = R.drawable.ic_wea_on_cloud;
			}
			
			// vectorString = displayDistance(distance) + " " + CbScience.englishDirection(angle);
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.precipitation))) {
			initial = "precip";
			vectorString = displayDistance(distance) + " " + CbScience.englishDirection(angle);
			if(condition.getPrecipitation_type().equals(mContext.getString(R.string.rain))) {
				switch((int)condition.getPrecipitation_amount()) {
				case 0:
					icon = R.drawable.ic_wea_on_rain1;
					politeReportText = mContext.getString(R.string.lightRain);
					break;
				case 1:
					icon = R.drawable.ic_wea_on_rain2;
					politeReportText = mContext.getString(R.string.moderateRain);
					break;
				case 2:
					icon = R.drawable.ic_wea_on_rain3;
					politeReportText = mContext.getString(R.string.heavyRain);
					break;
				default:
					icon = R.drawable.ic_wea_on_rain1;
					politeReportText = mContext.getString(R.string.rain);
				}
			} else if (condition.getPrecipitation_type().equals(mContext.getString(R.string.snow))) {
				switch((int)condition.getPrecipitation_amount()) {
				case 0:
					icon = R.drawable.ic_wea_on_snow1;
					politeReportText = mContext.getString(R.string.lightSnow);
					break;
				case 1:
					icon = R.drawable.ic_wea_on_snow2;
					politeReportText = mContext.getString(R.string.moderateSnow);
					break;
				case 2:
					icon = R.drawable.ic_wea_on_snow3;
					politeReportText = mContext.getString(R.string.heavySnow);
					break;
				default:
					icon = R.drawable.ic_wea_on_snow1;
					politeReportText = mContext.getString(R.string.snow);
				}
			} else if (condition.getPrecipitation_type().equals(mContext.getString(R.string.hail))) {
				switch((int)condition.getPrecipitation_amount()) {
				case 0:
					icon = R.drawable.ic_wea_on_hail1;
					politeReportText = mContext.getString(R.string.lightHail);
					break;
				case 1:
					icon = R.drawable.ic_wea_on_hail2;
					politeReportText = mContext.getString(R.string.moderateHail);
					break;
				case 2:
					icon = R.drawable.ic_wea_on_hail3;;
					politeReportText = mContext.getString(R.string.heavyHail);
					break;
				default:
					icon = R.drawable.ic_wea_on_hail1;
					politeReportText = mContext.getString(R.string.hail);
				}
			} else {
				icon = R.drawable.ic_wea_on_precip;
			}
			
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.thunderstorm))) {
			initial = "thunderstorm";
			icon = R.drawable.ic_wea_on_lightning2;
			vectorString = displayDistance(distance) + " " + CbScience.englishDirection(angle);
		} else if(condition.getGeneral_condition().equals(mContext.getString(R.string.extreme))) {
			initial = "severe";
			icon = R.drawable.ic_wea_on_severe;
			vectorString = displayDistance(distance) + " " + CbScience.englishDirection(angle);
			if(condition.getUser_comment().equals(mContext.getString(R.string.flooding))) {
				icon = R.drawable.ic_wea_on_flooding;
				politeReportText = mContext.getString(R.string.flooding);
			} else if(condition.getUser_comment().equals(mContext.getString(R.string.wildfire))) {
				icon = R.drawable.ic_wea_on_fire;
				politeReportText = mContext.getString(R.string.wildfire);
			} else if(condition.getUser_comment().equals(mContext.getString(R.string.tornado))) {
				icon = R.drawable.ic_wea_on_tornado;
				politeReportText = mContext.getString(R.string.tornado);
			} else if(condition.getUser_comment().equals(mContext.getString(R.string.duststorm))) {
				icon = R.drawable.ic_wea_on_dust;
				politeReportText = mContext.getString(R.string.duststorm);
			} else if(condition.getUser_comment().equals(mContext.getString(R.string.tropicalstorm))) {
				icon = R.drawable.ic_wea_on_tropical_storm;
				politeReportText = mContext.getString(R.string.tropicalstorm);
			}
		} 
		
		Notification.Builder mBuilder = new Notification.Builder(
				mContext).setSmallIcon(icon)
				.setContentTitle(politeReportText + " " + vectorString).setContentText(deliveryMessage);
		// Creates an explicit intent for an activity
		Intent resultIntent = new Intent(mContext,
				CurrentConditionsActivity.class);

		
		resultIntent.putExtra("latitude", notificationLatitude);
		resultIntent.putExtra("longitude", notificationLongitude);
		resultIntent.putExtra("cancelNotification", true);
		resultIntent.putExtra("initial", initial);
		resultIntent.putExtra("backToApp", true);

		try {
		
			android.support.v4.app.TaskStackBuilder stackBuilder = android.support.v4.app.TaskStackBuilder
					.create(mContext);
	
			stackBuilder.addNextIntent(resultIntent);
			PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
					PendingIntent.FLAG_UPDATE_CURRENT);
			mBuilder.setContentIntent(resultPendingIntent);
			NotificationManager mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
			// mId allows you to update the
			// notification later on.
			mNotificationManager.notify(CONDITION_NOTIFICATION_ID, mBuilder.build());
	
			AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
			Intent i = new Intent("ca.cumulonimbus.barometernetwork.CANCEL_CONDITION");
			PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i, 0);
			am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + (1000 * 60 * 60 * 2), pi);
					
			
			// Get tracker.
			Tracker t = getTracker(
			    TrackerName.APP_TRACKER);
			// Build and send an Event.
			t.send(new HitBuilders.EventBuilder()
			    .setCategory(BarometerNetworkActivity.GA_CATEGORY_NOTIFICATIONS)
			    .setAction("conditions_notification_delivered")
			    .setLabel(condition.getGeneral_condition())
			    .build());
			
			try {
				JSONObject props = new JSONObject();
				props.put("Condition", condition.getGeneral_condition());
				mixpanel.track("Notification Delivered", props);	
			} catch (JSONException jsone) {
				log("condition notification json exception " + jsone.getMessage());
			}
			
			mixpanel.flush();
			
			// save the time
			SharedPreferences.Editor editor = sharedPreferences.edit();
			editor.putLong("lastConditionTime", now);
			editor.commit();
		} catch(NoSuchMethodError nsme) {
			// 
		}
		
		// Get tracker.
		Tracker t = getTracker(
		    TrackerName.APP_TRACKER);
		// Build and send an Event.
		t.send(new HitBuilders.EventBuilder()
		    .setCategory(BarometerNetworkActivity.GA_CATEGORY_MAIN_APP)
		    .setAction("conditions_notification_delivered_final")
		    .setLabel(condition.getGeneral_condition())
		    .build());
	}
	
	private String displayDistance(double distance) {
		SharedPreferences sharedPreferences = PreferenceManager
				.getDefaultSharedPreferences(mContext);
		String preferredDistanceUnit = sharedPreferences.getString("distance_units", "Kilometers (km)");
		
		// Use km instead of m, even if preference is m
		if (preferredDistanceUnit.equals("Meters (m)")) {
			preferredDistanceUnit = "Kilometers (km)";
		}
		
		// Use mi instead of ft, even if preference is ft
		if (preferredDistanceUnit.equals("Feet (ft)")) {
			preferredDistanceUnit = "Miles (mi)";
		}
		
		DecimalFormat df = new DecimalFormat("##");
		DistanceUnit unit = new DistanceUnit(preferredDistanceUnit);
		unit.setValue(distance);
		unit.setAbbreviation(preferredDistanceUnit);
		double distanceInPreferredUnit = unit.convertToPreferredUnit();
		return df.format(distanceInPreferredUnit) + unit.fullToAbbrev();
	}
	
	
	/**
	 * Send an Android notification to the user with a notice of pressure
	 * tendency change.
	 * 
	 * @param tendencyChange
	 */
	private void deliverNotification(String tendencyChange) {
		SharedPreferences sharedPreferences = PreferenceManager
				.getDefaultSharedPreferences(mContext);

		long lastNotificationTime = sharedPreferences.getLong(
				"lastNotificationTime", System.currentTimeMillis()
						- (1000 * 60 * 60 * 10));
		long now = System.currentTimeMillis();
		long waitDiff = 1000 * 60 * 60 * 6;
		if (now - lastNotificationTime < waitDiff) {
			log("bailing on notification, not 6h wait yet");
			return;
		}

		String deliveryMessage = "";
		if (!tendencyChange.contains(",")) {
			// not returning to directional values? don't deliver notification
			return;
		}

		String first = tendencyChange.split(",")[0];
		String second = tendencyChange.split(",")[1];
		
		int smallIconId = R.drawable.ic_launcher;

		if ((first.contains("Rising")) && (second.contains("Falling"))) {
			deliveryMessage = mContext.getString(R.string.pressureDropNotification);
			smallIconId = R.drawable.ic_stat_notify_falling;
		} else if ((first.contains("Steady")) && (second.contains("Falling"))) {
			deliveryMessage = mContext.getString(R.string.pressureDropNotification);
			smallIconId = R.drawable.ic_stat_notify_falling;
		} else if ((first.contains("Steady")) && (second.contains("Rising"))) {
			deliveryMessage = mContext.getString(R.string.pressureRiseNotification);
			smallIconId = R.drawable.ic_stat_notify_rising;
		} else if ((first.contains("Falling")) && (second.contains("Rising"))) {
			deliveryMessage = mContext.getString(R.string.pressureRiseNotification);
			smallIconId = R.drawable.ic_stat_notify_rising;
		} else {
			deliveryMessage = mContext.getString(R.string.pressureSteadyNotification);
			// don't deliver this message
			log("bailing on notification, pressure is steady");
			return;
		}

		// View graph button
		Intent intent = new Intent(mContext, LogViewerActivity.class);
		PendingIntent graphIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

		// Creates an explicit intent for an activity
		android.support.v4.app.TaskStackBuilder stackBuilder = android.support.v4.app.TaskStackBuilder
				.create(mContext);
		Intent resultIntent = new Intent(mContext,
				CurrentConditionsActivity.class);
		// Current Conditions activity likes to know the location in the Intent
		double notificationLatitude = 0.0;
		double notificationLongitude = 0.0;
		try {
			LocationManager lm = (LocationManager) mContext
					.getSystemService(Context.LOCATION_SERVICE);
			Location loc = lm
					.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
			if (loc.getLatitude() != 0) {
				notificationLatitude = loc.getLatitude();
				notificationLongitude = loc.getLongitude();
			}
		} catch (Exception e) {

		}
		resultIntent.putExtra("latitude", notificationLatitude);
		resultIntent.putExtra("longitude", notificationLongitude);
		resultIntent.putExtra("cancelNotification", true);
		stackBuilder.addNextIntent(resultIntent);
		PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
				PendingIntent.FLAG_UPDATE_CURRENT);
		
		Notification.Builder mBuilder = new Notification.Builder(
				mContext).setSmallIcon(smallIconId)
				.setContentTitle(mContext.getString(R.string.app_name)).setContentText(deliveryMessage)
				.addAction(R.drawable.ic_menu_dark_stats, mContext.getString(R.string.viewGraph), graphIntent)
				.addAction(R.drawable.ic_menu_dark_weather, mContext.getString(R.string.reportWeather), resultPendingIntent);
		
		
		stackBuilder.addNextIntent(resultIntent);
		
		mBuilder.setContentIntent(resultPendingIntent);
		NotificationManager mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
		// mId allows you to update the
		// notification later on.
		mNotificationManager.notify(PRESSURE_NOTIFICATION_ID, mBuilder.build());
		
		AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
		Intent i = new Intent("ca.cumulonimbus.barometernetwork.CANCEL_PRESSURE");
		PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i, 0);
		am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + (1000 * 60 * 60 * 12), pi); // 12h
		
		

		// Get tracker.
		Tracker t = getTracker(
		    TrackerName.APP_TRACKER);
		// Build and send an Event.
		t.send(new HitBuilders.EventBuilder()
		    .setCategory(BarometerNetworkActivity.GA_CATEGORY_MAIN_APP)
		    .setAction("pressure_notification_delivered")
		    .setLabel(deliveryMessage)
		    .build());
				
			
		
		// save the time
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putLong("lastNotificationTime", now);
		editor.commit();

	}
	
	/**
	 * Given a condition, 
	 * @param condition
	 * @return
	 */
	private int getResIdForClearIcon(CbCurrentCondition condition) {
		int moonNumber = getMoonPhaseIndex();
		int sunDrawable = R.drawable.ic_wea_on_sun;
		LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
		Location location = lm.getLastKnownLocation("network");
		if(location != null) {
			if (!CurrentConditionsActivity.isDaytime(location
					.getLatitude(), location.getLongitude(), System.currentTimeMillis(), Calendar.getInstance().getTimeZone().getRawOffset())) {
				switch (moonNumber) {
				case 1:
					sunDrawable = R.drawable.ic_wea_on_moon1;
					break;
				case 2:
					sunDrawable = R.drawable.ic_wea_on_moon2;
					break;
				case 3:
					sunDrawable = R.drawable.ic_wea_on_moon3;
					break;
				case 4:
					sunDrawable = R.drawable.ic_wea_on_moon4;
					break;
				case 5:
					sunDrawable = R.drawable.ic_wea_on_moon5;
					break;
				case 6:
					sunDrawable = R.drawable.ic_wea_on_moon6;
					break;
				case 7:
					sunDrawable = R.drawable.ic_wea_on_moon7;
					break;
				case 8:
					sunDrawable = R.drawable.ic_wea_on_moon8;
					break;
				default:
					sunDrawable = R.drawable.ic_wea_on_moon2;
					break;
				}
			}
		}
		
		return sunDrawable;
	}
	
	/**
	 * Moon phase info
	 */
	private int getMoonPhaseIndex() {
		MoonPhase mp = new MoonPhase(Calendar.getInstance());
		return mp.getPhaseIndex();
	}
	
	private void log(String message) {
		if(PressureNETConfiguration.DEBUG_MODE) {
    		//logToFile(message);
    		System.out.println(message);
    	}
	}
}
