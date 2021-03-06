/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.internal.operators;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static rx.internal.operators.OnSubscribeAmb.amb;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.*;
import org.mockito.InOrder;

import rx.*;
import rx.Observable.OnSubscribe;
import rx.exceptions.TestException;
import rx.functions.*;
import rx.internal.util.RxRingBuffer;
import rx.observers.TestSubscriber;
import rx.schedulers.*;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

public class OnSubscribeAmbTest {

    private TestScheduler scheduler;
    private Scheduler.Worker innerScheduler;

    @Before
    public void setUp() {
        scheduler = new TestScheduler();
        innerScheduler = scheduler.createWorker();
    }

    private Observable<String> createObservable(final String[] values,
            final long interval, final Throwable e) {
        return Observable.unsafeCreate(new OnSubscribe<String>() {

            @Override
            public void call(final Subscriber<? super String> subscriber) {
                CompositeSubscription parentSubscription = new CompositeSubscription();
                subscriber.add(parentSubscription);
                long delay = interval;
                for (final String value : values) {
                    parentSubscription.add(innerScheduler.schedule(new Action0() {
                        @Override
                        public void call() {
                            subscriber.onNext(value);
                        }
                    }, delay, TimeUnit.MILLISECONDS));
                    delay += interval;
                }
                parentSubscription.add(innerScheduler.schedule(new Action0() {
                    @Override
                    public void call() {
                        if (e == null) {
                            subscriber.onCompleted();
                        } else {
                            subscriber.onError(e);
                        }
                    }
                }, delay, TimeUnit.MILLISECONDS));
            }
        });
    }

    @Test
    public void testAmb() {
        Observable<String> observable1 = createObservable(new String[] {
                "1", "11", "111", "1111" }, 2000, null);
        Observable<String> observable2 = createObservable(new String[] {
                "2", "22", "222", "2222" }, 1000, null);
        Observable<String> observable3 = createObservable(new String[] {
                "3", "33", "333", "3333" }, 3000, null);

        Observable<String> o = Observable.unsafeCreate(amb(observable1,
                observable2, observable3));

        @SuppressWarnings("unchecked")
        Observer<String> observer = mock(Observer.class);
        o.subscribe(observer);

        scheduler.advanceTimeBy(100000, TimeUnit.MILLISECONDS);

        InOrder inOrder = inOrder(observer);
        inOrder.verify(observer, times(1)).onNext("2");
        inOrder.verify(observer, times(1)).onNext("22");
        inOrder.verify(observer, times(1)).onNext("222");
        inOrder.verify(observer, times(1)).onNext("2222");
        inOrder.verify(observer, times(1)).onCompleted();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testAmb2() {
        IOException expectedException = new IOException(
                "fake exception");
        Observable<String> observable1 = createObservable(new String[] {},
                2000, new IOException("fake exception"));
        Observable<String> observable2 = createObservable(new String[] {
                "2", "22", "222", "2222" }, 1000, expectedException);
        Observable<String> observable3 = createObservable(new String[] {},
                3000, new IOException("fake exception"));

        Observable<String> o = Observable.unsafeCreate(amb(observable1,
                observable2, observable3));

        @SuppressWarnings("unchecked")
        Observer<String> observer = mock(Observer.class);
        o.subscribe(observer);

        scheduler.advanceTimeBy(100000, TimeUnit.MILLISECONDS);

        InOrder inOrder = inOrder(observer);
        inOrder.verify(observer, times(1)).onNext("2");
        inOrder.verify(observer, times(1)).onNext("22");
        inOrder.verify(observer, times(1)).onNext("222");
        inOrder.verify(observer, times(1)).onNext("2222");
        inOrder.verify(observer, times(1)).onError(expectedException);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testAmb3() {
        Observable<String> observable1 = createObservable(new String[] {
                "1" }, 2000, null);
        Observable<String> observable2 = createObservable(new String[] {},
                1000, null);
        Observable<String> observable3 = createObservable(new String[] {
                "3" }, 3000, null);

        Observable<String> o = Observable.unsafeCreate(amb(observable1,
                observable2, observable3));

        @SuppressWarnings("unchecked")
        Observer<String> observer = mock(Observer.class);
        o.subscribe(observer);

        scheduler.advanceTimeBy(100000, TimeUnit.MILLISECONDS);
        InOrder inOrder = inOrder(observer);
        inOrder.verify(observer, times(1)).onCompleted();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testProducerRequestThroughAmb() {
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>();
        ts.requestMore(3);
        final AtomicLong requested1 = new AtomicLong();
        final AtomicLong requested2 = new AtomicLong();
        Observable<Integer> o1 = Observable.unsafeCreate(new OnSubscribe<Integer>() {

            @Override
            public void call(Subscriber<? super Integer> s) {
                s.setProducer(new Producer() {

                    @Override
                    public void request(long n) {
                        System.out.println("1-requested: " + n);
                        requested1.set(n);
                    }

                });
            }

        });
        Observable<Integer> o2 = Observable.unsafeCreate(new OnSubscribe<Integer>() {

            @Override
            public void call(Subscriber<? super Integer> s) {
                s.setProducer(new Producer() {

                    @Override
                    public void request(long n) {
                        System.out.println("2-requested: " + n);
                        requested2.set(n);
                    }

                });
            }

        });
        Observable.amb(o1, o2).subscribe(ts);
        assertEquals(3, requested1.get());
        assertEquals(3, requested2.get());
    }

    @Test
    public void testBackpressure() {
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>();
        Observable.range(0, RxRingBuffer.SIZE * 2)
                .ambWith(Observable.range(0, RxRingBuffer.SIZE * 2))
                .observeOn(Schedulers.computation()) // observeOn has a backpressured RxRingBuffer
                .delay(1, TimeUnit.MICROSECONDS) // make it a slightly slow consumer
                .subscribe(ts);

        ts.awaitTerminalEvent();
        ts.assertNoErrors();
        assertEquals(RxRingBuffer.SIZE * 2, ts.getOnNextEvents().size());
    }


    @Test
    public void testSubscriptionOnlyHappensOnce() throws InterruptedException {
        final AtomicLong count = new AtomicLong();
        Action0 incrementer = new Action0() {
            @Override
            public void call() {
                count.incrementAndGet();
            }
        };
        //this aync stream should emit first
        Observable<Integer> o1 = Observable.just(1).doOnSubscribe(incrementer)
                .delay(100, TimeUnit.MILLISECONDS).subscribeOn(Schedulers.computation());
        //this stream emits second
        Observable<Integer> o2 = Observable.just(1).doOnSubscribe(incrementer)
                .delay(100, TimeUnit.MILLISECONDS).subscribeOn(Schedulers.computation());
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>();
        Observable.amb(o1, o2).subscribe(ts);
        ts.requestMore(1);
        ts.awaitTerminalEvent(5, TimeUnit.SECONDS);
        ts.assertNoErrors();
        assertEquals(2, count.get());
    }

    @Test
    public void testSecondaryRequestsPropagatedToChildren() throws InterruptedException {
        //this aync stream should emit first
        Observable<Integer> o1 = Observable.from(Arrays.asList(1, 2, 3))
                .delay(100, TimeUnit.MILLISECONDS).subscribeOn(Schedulers.computation());
        //this stream emits second
        Observable<Integer> o2 = Observable.from(Arrays.asList(4, 5, 6))
                .delay(200, TimeUnit.MILLISECONDS).subscribeOn(Schedulers.computation());
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>() {
            @Override
            public void onStart() {
                request(1);
            }};
        Observable.amb(o1, o2).subscribe(ts);
        // before first emission request 20 more
        // this request should suffice to emit all
        ts.requestMore(20);
        //ensure stream does not hang
        ts.awaitTerminalEvent(5, TimeUnit.SECONDS);
        ts.assertNoErrors();
    }

    @Test
    public void testSynchronousSources() {
        // under async subscription the second observable would complete before
        // the first but because this is a synchronous subscription to sources
        // then second observable does not get subscribed to before first
        // subscription completes hence first observable emits result through
        // amb
        int result = Observable.just(1).doOnNext(new Action1<Object>() {

            @Override
            public void call(Object t) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    //
                }
            }
        }).ambWith(Observable.just(2)).toBlocking().single();
        assertEquals(1, result);
    }

    @Test(timeout = 1000)
    public void testMultipleUse() {
        TestSubscriber<Long> ts1 = new TestSubscriber<Long>();
        TestSubscriber<Long> ts2 = new TestSubscriber<Long>();

        Observable<Long> amb = Observable.timer(100, TimeUnit.MILLISECONDS).ambWith(Observable.timer(200, TimeUnit.MILLISECONDS));

        amb.subscribe(ts1);
        amb.subscribe(ts2);

        ts1.awaitTerminalEvent();
        ts2.awaitTerminalEvent();

        ts1.assertValue(0L);
        ts1.assertCompleted();
        ts1.assertNoErrors();

        ts2.assertValue(0L);
        ts2.assertCompleted();
        ts2.assertNoErrors();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void ambIterable() {
        PublishSubject<Integer> ps1 = PublishSubject.create();
        PublishSubject<Integer> ps2 = PublishSubject.create();

        TestSubscriber<Integer> ts = TestSubscriber.create();

        Observable.amb(Arrays.asList(ps1, ps2)).subscribe(ts);

        ts.assertNoValues();

        ps1.onNext(1);
        ps1.onCompleted();

        assertFalse(ps1.hasObservers());
        assertFalse(ps2.hasObservers());

        ts.assertValue(1);
        ts.assertNoErrors();
        ts.assertCompleted();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void ambIterable2() {
        PublishSubject<Integer> ps1 = PublishSubject.create();
        PublishSubject<Integer> ps2 = PublishSubject.create();

        TestSubscriber<Integer> ts = TestSubscriber.create();

        Observable.amb(Arrays.asList(ps1, ps2)).subscribe(ts);

        ts.assertNoValues();

        ps2.onNext(2);
        ps2.onCompleted();

        assertFalse(ps1.hasObservers());
        assertFalse(ps2.hasObservers());

        ts.assertValue(2);
        ts.assertNoErrors();
        ts.assertCompleted();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void ambMany() throws Exception {
        for (int i = 2; i < 10; i++) {
            Class<?>[] clazz = new Class[i];
            Arrays.fill(clazz, Observable.class);

            PublishSubject<Integer>[] ps = new PublishSubject[i];

            for (int j = 0; j < i; j++) {

                for (int k = 0; k < i; k++) {
                    ps[k] = PublishSubject.create();
                }

                Method m = Observable.class.getMethod("amb", clazz);

                Observable<Integer> obs = (Observable<Integer>)m.invoke(null, (Object[])ps);

                TestSubscriber<Integer> ts = TestSubscriber.create();

                obs.subscribe(ts);

                for (int k = 0; k < i; k++) {
                    assertTrue("@" + i + "/" + k + " has no observers?", ps[k].hasObservers());
                }

                ps[j].onNext(j);
                ps[j].onCompleted();

                for (int k = 0; k < i; k++) {
                    assertFalse("@" + i + "/" + k + " has observers?", ps[k].hasObservers());
                }

                ts.assertValue(j);
                ts.assertNoErrors();
                ts.assertCompleted();
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void ambManyError() throws Exception {
        for (int i = 2; i < 10; i++) {
            Class<?>[] clazz = new Class[i];
            Arrays.fill(clazz, Observable.class);

            PublishSubject<Integer>[] ps = new PublishSubject[i];

            for (int j = 0; j < i; j++) {

                for (int k = 0; k < i; k++) {
                    ps[k] = PublishSubject.create();
                }

                Method m = Observable.class.getMethod("amb", clazz);

                Observable<Integer> obs = (Observable<Integer>)m.invoke(null, (Object[])ps);

                TestSubscriber<Integer> ts = TestSubscriber.create();

                obs.subscribe(ts);

                for (int k = 0; k < i; k++) {
                    assertTrue("@" + i + "/" + k + " has no observers?", ps[k].hasObservers());
                }

                ps[j].onError(new TestException(Integer.toString(j)));

                for (int k = 0; k < i; k++) {
                    assertFalse("@" + i + "/" + k + " has observers?", ps[k].hasObservers());
                }

                ts.assertNoValues();
                ts.assertError(TestException.class);
                ts.assertNotCompleted();

                assertEquals(Integer.toString(j), ts.getOnErrorEvents().get(0).getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void ambManyComplete() throws Exception {
        for (int i = 2; i < 10; i++) {
            Class<?>[] clazz = new Class[i];
            Arrays.fill(clazz, Observable.class);

            PublishSubject<Integer>[] ps = new PublishSubject[i];

            for (int j = 0; j < i; j++) {

                for (int k = 0; k < i; k++) {
                    ps[k] = PublishSubject.create();
                }

                Method m = Observable.class.getMethod("amb", clazz);

                Observable<Integer> obs = (Observable<Integer>)m.invoke(null, (Object[])ps);

                TestSubscriber<Integer> ts = TestSubscriber.create();

                obs.subscribe(ts);

                for (int k = 0; k < i; k++) {
                    assertTrue("@" + i + "/" + k + " has no observers?", ps[k].hasObservers());
                }

                ps[j].onCompleted();

                for (int k = 0; k < i; k++) {
                    assertFalse("@" + i + "/" + k + " has observers?", ps[k].hasObservers());
                }

                ts.assertNoValues();
                ts.assertNoErrors();
                ts.assertCompleted();
            }
        }
    }
}
