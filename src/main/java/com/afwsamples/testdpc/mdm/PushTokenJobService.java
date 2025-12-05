package com.afwsamples.testdpc.mdm;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** JobScheduler hook to retry FCM push token registration with backoff. */
public class PushTokenJobService extends JobService {

  @Override
  public boolean onStartJob(JobParameters params) {
    new Thread(
            () -> {
              FcmPushManager.syncFromJob(getApplicationContext());
              jobFinished(params, false);
            })
        .start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return true; // reschedule if the job was interrupted
  }
}
