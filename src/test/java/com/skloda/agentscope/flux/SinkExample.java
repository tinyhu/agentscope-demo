package com.skloda.agentscope.flux;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

public class SinkExample {
    public static void main(String[] args) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<String> flux = sink.asFlux();

        flux.subscribe(System.out::println);

        sink.emitNext("Hello1", Sinks.EmitFailureHandler.FAIL_FAST);

        sink.tryEmitComplete();

        // 使用 tryEmitNext 推送元素
        if (sink.tryEmitNext("Hello2").isSuccess()) {
            System.out.println("元素推送成功");
        }

        sink.emitNext("Hello3", Sinks.EmitFailureHandler.FAIL_FAST);
    }
}