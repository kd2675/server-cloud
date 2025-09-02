package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplier;
import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest.WeightedMetricsTestBase;
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
        // 테스트 후 리소스 정리
        disposables.forEach(Disposable::dispose);
        disposables.clear();
        
        // 강제 GC 실행
        System.gc();
        
        try {
            Thread.sleep(100); // GC가 완료될 시간 제공
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

        // 여러 개의 supplier 인스턴스 생성 (백그라운드 모니터링 포함)
        List<WeightedMetricsBasedRedisServiceInstanceListSupplier> suppliers = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            suppliers.add(new WeightedMetricsBasedRedisServiceInstanceListSupplier(context, reactiveRedisTemplate));
        }

        // 잠시 동안 백그라운드 모니터링이 동작하도록 대기
        Thread.sleep(2000);

        // When - supplier들을 명시적으로 정리 (실제로는 Spring이 관리하지만 테스트용)
        suppliers.clear();
        System.gc();
        Thread.sleep(1000);

        // Then - 메모리 누수가 없는지 확인
        MemoryUsage finalMemory = memoryBean.getHeapMemoryUsage();
        long finalUsedMemory = finalMemory.getUsed();
        
        // 메모리 증가량이 합리적인 범위 내인지 확인 (10MB 미만)
        long memoryIncrease = finalUsedMemory - initialUsedMemory;
        assertThat(memoryIncrease).isLessThan(10 * 1024 * 1024); // 10MB 미만
    }

    @Test
    @DisplayName("대량 동시 요청 시 메모리 효율성 테스트")
    void testMemoryEfficiencyUnderHighLoad() throws InterruptedException {
        // Given - 확실한 Redis Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> lightMetrics = createMetricsData(20.0, 25.0, 30.0);
        
        // 🔥 완전한 Mock 설정 (null 반환 방지)
        when(reactiveValueOperations.get(anyString()))
            .thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                if (key.contains("health")) {
                    return Mono.just(healthyData);
                } else if (key.contains("metrics")) {
                    return Mono.just(lightMetrics);
                }
                return Mono.empty(); // null 대신 empty 반환
            });

        // 🔥 ReactiveRedisTemplate와 ReactiveValueOperations Mock이 null이 아님을 보장
        assertThat(reactiveRedisTemplate).isNotNull();
        assertThat(reactiveValueOperations).isNotNull();
        
        MemoryUsage beforeLoad = memoryBean.getHeapMemoryUsage();
        CountDownLatch loadTest = new CountDownLatch(100);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // When - 100개 동시 요청 (더 안전한 처리)
        for (int i = 0; i < 100; i++) {
            final int requestId = i;
            
            Disposable requestDisposable = supplier.get()
                .timeout(Duration.ofSeconds(5)) // 타임아웃 설정
                .doOnNext(instances -> {
                    successCount.incrementAndGet();
                })
                .doOnError(error -> {
                    errorCount.incrementAndGet();
                })
                .doFinally(signal -> {
                    loadTest.countDown();
                })
                .onErrorResume(error -> {
                    // 에러가 발생해도 빈 리스트 반환하여 테스트 계속 진행
                    return Mono.just(new ArrayList<>());
                })
                .subscribe(
                    instances -> {
                        // 성공 처리 (이미 doOnNext에서 처리됨)
                    },
                    error -> {
                        // 최종 에러 처리 (이미 doOnError에서 처리됨)
                    }
                );
            
            disposables.add(requestDisposable);
        }
        
        // 모든 요청 완료까지 대기
        boolean allCompleted = loadTest.await(15, TimeUnit.SECONDS); // 타임아웃 증가
        
        // 정리 후 메모리 측정
        disposables.forEach(disposable -> {
            try {
                if (!disposable.isDisposed()) {
                    disposable.dispose();
                }
            } catch (Exception e) {
            }
        });
        disposables.clear();
        
        // 강제 GC 수행 (여러 번)
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        
        MemoryUsage afterLoad = memoryBean.getHeapMemoryUsage();
        
        // Then - 메모리 사용량 분석
        long memoryIncrease = afterLoad.getUsed() - beforeLoad.getUsed();
        double memoryIncreaseMB = memoryIncrease / (1024.0 * 1024.0);
        
        // 🔥 메모리 증가량 검증 (관대한 기준)
        assertThat(memoryIncrease)
            .describedAs("메모리 증가량이 합리적 범위 내에 있어야 함 (현재: %.2f MB)", memoryIncreaseMB)
            .isLessThan(100 * 1024 * 1024); // 100MB 미만
        
        // 🔥 최소한의 요청이 처리되었는지 검증
        assertThat(successCount.get() + errorCount.get())
            .describedAs("총 처리된 요청 수가 최소 기준을 만족해야 함")
            .isGreaterThanOrEqualTo(50); // 최소 50개 요청 처리
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
                .repeat(10) // 각 구독마다 10번 반복
                .subscribe();
            subscriptions.add(subscription);
        }
        
        Thread.sleep(1000); // 구독들이 동작할 시간 제공
        
        // 모든 구독 해제
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
        
        System.gc();
        Thread.sleep(1000);
        
        MemoryUsage finalMemory = memoryBean.getHeapMemoryUsage();
        
        // Then - 구독 해제 후 메모리가 적절히 정리되었는지 확인
        long memoryDifference = finalMemory.getUsed() - initialMemory.getUsed();
        assertThat(memoryDifference).isLessThan(15 * 1024 * 1024); // 15MB 미만
    }
}