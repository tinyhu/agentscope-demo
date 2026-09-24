package com.skloda.agentscope.flux;

import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class FluxExample {
    public static void main(String[] args) {
        Flux<String> test = Flux.just("清水", "洗洁精", "洗碗水");
        test.subscribe(s -> System.out.println("正在处理：" + s));


        Flux<Integer> test2= Flux.range(1, 10)
                .map(i -> i * 2)
                .filter(i -> i % 3 == 0);
        test2.subscribe(s-> System.out.println("数据："+s));

        Flux<Integer> flux = Flux.range(1, 19);
        flux.subscribe(new BaseSubscriber<Integer>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(20); // 仅请求 10 条
            }

            @Override
            protected void hookOnNext(Integer value) {
                System.out.println("Received: " + value);
                if (value == 11) {
                    cancel(); // 手动取消订阅
                }
            }
        });


        Flux.range(1, 10000)
                .onBackpressureBuffer(22,
                        dropped -> System.out.println("Dropped: " + dropped))
                .publishOn(Schedulers.parallel(), 10)
                .subscribe(System.out::println);
    }
}
