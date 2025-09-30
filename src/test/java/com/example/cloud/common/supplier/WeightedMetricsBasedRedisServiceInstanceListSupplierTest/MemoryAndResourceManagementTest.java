package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.EurekaWeightedBasedRedisInstanceSupplier;
import com.example.cloud.common.supplier.ExtendedServiceInstanceListSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("메모리 및 리소스 관리 테스트")
class MemoryAndResourceManagementTest extends WeightedMetricsTestBase {

    private final List<Disposable> disposables = new ArrayList<>();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    @AfterEach
    void cleanup() {
        disposables.forEach(d -> {
            if (!d.isDisposed()) d.dispose();
        });
        disposables.clear();
        
        // 강제 GC
        System.gc();
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("백그라운드 모니터링 리소스 정리 테스트")
    void testBackgroundMonitoringResourceCleanup() throws InterruptedException {
        // Given - 메모리 사용량 측정 시작
        MemoryUsage initialMemory = memoryBean.getHeapMemoryUsage();
        long initialUsedMemory = initialMemory.getUsed();

        // 여러 supplier 인스턴스 생성
        List<ExtendedServiceInstanceListSupplier> suppliers = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            suppliers.add(new EurekaWeightedBasedRedisInstanceSupplier(
                context, discoveryClient, reactiveRedisTemplate));
        }

        Thread.sleep(2000);

        // When - 정리
        suppliers.clear();
        System.gc();
        Thread.sleep(1000);

        // Then - 메모리 누수 확인
        MemoryUsage finalMemory = memoryBean.getHeapMemoryUsage();
        long finalUsedMemory = finalMemory.getUsed();
        
        long memoryIncrease = finalUsedMemory - initialUsedMemory;
        assertThat(memoryIncrease).isLessThan(10 * 1024 * 1024); // 10MB 미만
    }

    @Test
    @DisplayName("대량 동시 요청 시 메모리 효율성 테스트")
    void testMemoryEfficiencyUnderHighLoad() throws InterruptedException {
        // Given - 안정적인 Redis Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> lightMetrics = createMetricsData(20.0, 25.0, 30.0);
        
        when(reactiveValueOperations.get(anyString()))
            .thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                if (key.contains("health")) {
                    return Mono.just(healthyData);
                } else if (key.contains("metrics")) {
                    return Mono.just(lightMetrics);
                }
                return Mono.empty();
            });

        MemoryUsage beforeLoad = memoryBean.getHeapMemoryUsage();
        CountDownLatch loadTest = new CountDownLatch(100);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // When - 100개 동시 요청
        for (int i = 0; i < 100; i++) {
            Disposable requestDisposable = supplier.get()
                .timeout(Duration.ofSeconds(5))
                .doOnNext(instances -> successCount.incrementAndGet())
                .doOnError(error -> errorCount.incrementAndGet())
                .doFinally(signal -> loadTest.countDown())
                .onErrorResume(error -> Mono.just(new ArrayList<>()))
                .subscribe();
            
            disposables.add(requestDisposable);
        }
        
        boolean allCompleted = loadTest.await(15, TimeUnit.SECONDS);
        
        // 정리
        disposables.forEach(d -> {
            if (!d.isDisposed()) d.dispose();
        });
        disposables.clear();
        
        // 강제 GC
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        
        MemoryUsage afterLoad = memoryBean.getHeapMemoryUsage();
        
        // Then
        long memoryIncrease = afterLoad.getUsed() - beforeLoad.getUsed();
        double memoryIncreaseMB = memoryIncrease / (1024.0 * 1024.0);
        
        assertThat(memoryIncrease)
            .describedAs("메모리 증가량: %.2f MB", memoryIncreaseMB)
            .isLessThan(100 * 1024 * 1024); // 100MB 미만
        
        assertThat(successCount.get() + errorCount.get())
            .describedAs("최소 50개 요청 처리")
            .isGreaterThanOrEqualTo(50);
    }

    @Test
    @DisplayName("Flux 구독 해제 시 리소스 정리 확인")
    void testFluxSubscriptionResourceCleanup() throws InterruptedException {
        // Given
        List<Disposable> subscriptions = new ArrayList<>();
        MemoryUsage initialMemory = memoryBean.getHeapMemoryUsage();
        
        // When - 많은 구독 생성 후 해제
        for (int i = 0; i < 50; i++) {
            Disposable subscription = supplier.get()
                .repeat(10)
                .subscribe();
            subscriptions.add(subscription);
        }
        
        Thread.sleep(1000);
        
        // 모든 구독 해제
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
        
        System.gc();
        Thread.sleep(1000);
        
        MemoryUsage finalMemory = memoryBean.getHeapMemoryUsage();
        
        // Then
        long memoryDifference = finalMemory.getUsed() - initialMemory.getUsed();
        assertThat(memoryDifference).isLessThan(15 * 1024 * 1024); // 15MB 미만
    }
}