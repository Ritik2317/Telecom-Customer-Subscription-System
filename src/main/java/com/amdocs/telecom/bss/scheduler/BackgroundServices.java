package com.amdocs.telecom.bss.scheduler;

import com.amdocs.telecom.bss.service.BillingService;
import java.util.concurrent.*;
import java.util.logging.*;

/** Durable database queues are authoritative; this bounded queue only wakes the usage worker. */
public final class BackgroundServices implements AutoCloseable {
    private final ScheduledExecutorService scheduled=Executors.newScheduledThreadPool(3);
    private final ExecutorService usage=Executors.newSingleThreadExecutor();
    private final BlockingQueue<Boolean> wakeups=new ArrayBlockingQueue<>(64);
    private final BillingService service;
    private volatile boolean running;
    private Future<?> usageTask;
    private static final Logger LOG=Logger.getLogger(BackgroundServices.class.getName());
    public BackgroundServices(BillingService service) { this.service=service; }
    public synchronized void start() {
        if(running) return;running=true;
        schedule("BillingScheduler",service::generateMonthlyBills,3600);
        schedule("PaymentNotificationService",service::deliverNotifications,2);
        schedule("AccountMonitor",service::monitorAccounts,60);
        Callable<Void> processor=()->{while(running && !Thread.currentThread().isInterrupted()) {
            try {wakeups.poll(2,TimeUnit.SECONDS);if(running) guarded("UsageProcessor",service::processPendingUsage);}
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();break;}
        }return null;};
        usageTask=usage.submit(processor);
    }
    private void schedule(String name,Runnable job,long seconds) {scheduled.scheduleWithFixedDelay(()->guarded(name,job),0,seconds,TimeUnit.SECONDS);}
    private void guarded(String name,Runnable job) {
        try {job.run();}catch(RuntimeException error) {LOG.warning(name+" failed; durable work remains pending. Error type: "+error.getClass().getSimpleName());}
    }
    public boolean wakeUsage() {return wakeups.offer(Boolean.TRUE);}
    public boolean isRunning() {return running && usageTask!=null && !usageTask.isDone();}
    @Override public synchronized void close() {
        running=false;scheduled.shutdownNow();usage.shutdownNow();
        try {scheduled.awaitTermination(5,TimeUnit.SECONDS);usage.awaitTermination(5,TimeUnit.SECONDS);}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
    }
}
