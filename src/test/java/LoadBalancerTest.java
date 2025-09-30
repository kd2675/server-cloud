import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.ArrayList;
import java.util.List;
import reactor.core.Disposable;

public class LoadBalancerTest {
    
    private final WebClient webClient = WebClient.builder()
            .baseUrl("http://kimd0.iptime.org:10180")
            .build();
    
    @Test
    public void testLoadBalancing() throws InterruptedException {
        int totalRequests = 50;
        int concurrentRequests = 10;
        
        Map<String, AtomicInteger> instanceCounts = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        System.out.println("=== 로드밸런싱 부하 테스트 시작 ===");
        System.out.println("총 요청수: " + totalRequests);
        System.out.println("동시 요청수: " + concurrentRequests);
        
        long startTime = System.currentTimeMillis();
        
        // 동시 요청 생성
        Flux.range(0, totalRequests)
                .flatMap(i -> 
                    webClient.get()
                            .uri("/service/batch/metrics/load")
                            .header("Auth-header", "second")
                            .retrieve()
                            .bodyToMono(Map.class)
                            .map(response -> {
                                String instanceId = (String) response.get("instanceId");
                                instanceCounts.computeIfAbsent(instanceId, k -> new AtomicInteger(0))
                                             .incrementAndGet();
                                successCount.incrementAndGet();
                                System.out.println("Request " + i + " -> " + instanceId);
                                return response;
                            })
                            .doOnError(error -> {
                                failureCount.incrementAndGet();
                                System.err.println("Request " + i + " failed: " + error.getMessage());
                            })
                            .onErrorResume(error -> Mono.empty())
                            .doFinally(signal -> latch.countDown())
                            .subscribeOn(Schedulers.parallel()),
                    concurrentRequests) // 동시 실행 제한
                .subscribe();
        
        // 모든 요청 완료 대기
        latch.await();
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        
        // 결과 출력
        System.out.println("\n=== 테스트 결과 ===");
        System.out.println("총 실행 시간: " + totalTime + "ms");
        System.out.println("성공: " + successCount.get() + " / 실패: " + failureCount.get());
        System.out.println("초당 요청수 (TPS): " + String.format("%.2f", (double) successCount.get() / (totalTime / 1000.0)));
        
        System.out.println("\n=== 인스턴스별 분배 결과 ===");
        instanceCounts.forEach((instanceId, count) -> {
            double percentage = (double) count.get() / successCount.get() * 100;
            System.out.println(instanceId + ": " + count.get() + "회 (" + String.format("%.1f", percentage) + "%)");
        });
        
        // 로드밸런싱이 제대로 되었는지 확인
        if (instanceCounts.size() > 1) {
            System.out.println("\n✅ 로드밸런싱 정상 작동 - " + instanceCounts.size() + "개 인스턴스에 분산됨");
        } else {
            System.out.println("\n⚠️ 로드밸런싱 확인 필요 - 1개 인스턴스에만 요청됨");
        }
    }
    
    @Test
    public void continuousLoadTest() {
        System.out.println("=== 지속적인 부하 테스트 (30초) ===");
        
        Map<String, AtomicInteger> instanceCounts = new ConcurrentHashMap<>();
        AtomicInteger totalRequests = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // ⭐ 핵심 해결책: onBackpressureBuffer 또는 onBackpressureDrop 사용
        Disposable disposable = Flux.interval(Duration.ofMillis(100)) // 100ms로 간격 조정
                .take(Duration.ofSeconds(10))
                .onBackpressureBuffer(1000) // 백프레셔 버퍼 추가
                .flatMap(i -> {
                    totalRequests.incrementAndGet();
                    return webClient.get()
                            .uri("/service/batch/metrics/load")
                            .header("Auth-header", "second")
                            .retrieve()
                            .bodyToMono(Map.class)
                            .timeout(Duration.ofSeconds(5)) // 타임아웃 추가
                            .map(response -> {
                                String instanceId = (String) response.get("instanceId");
                                Double loadScore = (Double) response.get("loadScore");
                                
                                instanceCounts.computeIfAbsent(instanceId, k -> new AtomicInteger(0))
                                             .incrementAndGet();
                                successCount.incrementAndGet();
                                
                                if (successCount.get() % 10 == 0) { // 10번마다 출력
                                    System.out.println("Success " + successCount.get() + 
                                                     " -> " + instanceId + 
                                                     " (Load: " + String.format("%.2f", loadScore) + ")");
                                }
                                return response;
                            })
                            .onErrorResume(error -> {
                                errorCount.incrementAndGet();
                                System.err.println("Request failed: " + error.getMessage());
                                return Mono.empty();
                            });
                }, 3) // 동시 최대 3개 요청으로 제한
                .doOnComplete(() -> printResults(totalRequests, successCount, errorCount, instanceCounts))
                .doOnError(error -> {
                    System.err.println("테스트 중단: " + error.getMessage());
                    printResults(totalRequests, successCount, errorCount, instanceCounts);
                })
                .subscribe(); // ⭐ blockLast() 대신 subscribe() 사용
        
        // 30초 대기
        try {
            Thread.sleep(11000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            disposable.dispose(); // 리소스 정리
        }
        
        printResults(totalRequests, successCount, errorCount, instanceCounts);
    }

    private void printResults(AtomicInteger totalRequests, AtomicInteger successCount, 
                             AtomicInteger errorCount, Map<String, AtomicInteger> instanceCounts) {
        System.out.println("\n=== 지속 테스트 완료 ===");
        System.out.println("총 시도: " + totalRequests.get());
        System.out.println("성공: " + successCount.get());
        System.out.println("실패: " + errorCount.get());
        System.out.println("성공률: " + String.format("%.1f%%", 
                          (double) successCount.get() / totalRequests.get() * 100));
        
        if (!instanceCounts.isEmpty()) {
            System.out.println("\n=== 최종 분배 결과 ===");
            instanceCounts.forEach((instanceId, count) -> {
                double percentage = (double) count.get() / successCount.get() * 100;
                System.out.println(instanceId + ": " + count.get() + "회 (" + 
                                 String.format("%.1f", percentage) + "%)");
            });
        }
    }

    @Test
    public void improvedLoadTest() {
        System.out.println("=== 개선된 부하 테스트 ===");
        
        int totalRequests = 100;
        int batchSize = 5; // 배치당 동시 요청 수
        int batchInterval = 300; // 배치 간격 (ms)
        
        Map<String, AtomicInteger> instanceCounts = new ConcurrentHashMap<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // 배치 단위로 요청 처리
        List<Mono<Void>> batches = new ArrayList<>();
        
        for (int batch = 0; batch < totalRequests; batch += batchSize) {
            final int batchStart = batch;
            
            Mono<Void> batchMono = Mono.delay(Duration.ofMillis(batch * batchInterval / batchSize))
                    .then(Flux.range(batchStart, Math.min(batchSize, totalRequests - batchStart))
                            .flatMap(i -> makeRequest(i, instanceCounts, successCount, errorCount))
                            .then());
            
            batches.add(batchMono);
        }
        
        // 모든 배치 실행 및 대기
        Mono.when(batches)
                .doOnSuccess(v -> System.out.println("모든 배치 완료!"))
                .doOnError(error -> System.err.println("배치 실행 오류: " + error.getMessage()))
                .block(Duration.ofMinutes(2)); // 최대 2분 대기
        
        // 결과 출력
        printFinalResults(totalRequests, successCount, errorCount, instanceCounts);
    }

    private Mono<Void> makeRequest(int requestId, Map<String, AtomicInteger> instanceCounts, 
                                  AtomicInteger successCount, AtomicInteger errorCount) {
        return webClient.get()
                .uri("/service/batch/metrics/load")
                .header("Auth-header", "second")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .map(response -> {
                    String instanceId = (String) response.get("instanceId");
                    Double loadScore = (Double) response.get("loadScore");
                    
                    instanceCounts.computeIfAbsent(instanceId, k -> new AtomicInteger(0))
                                 .incrementAndGet();
                    successCount.incrementAndGet();
                    
                    System.out.println("Request " + requestId + " SUCCESS -> " + instanceId + 
                                     " (Load: " + String.format("%.2f", loadScore) + ")");
                    return response;
                })
                .onErrorResume(error -> {
                    errorCount.incrementAndGet();
                    System.err.println("Request " + requestId + " FAILED: " + error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private void printFinalResults(int totalRequests, AtomicInteger successCount, 
                                  AtomicInteger errorCount, Map<String, AtomicInteger> instanceCounts) {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("🎯 부하 테스트 최종 결과");
        System.out.println("=".repeat(50));
        System.out.println("총 요청: " + totalRequests);
        System.out.println("성공: " + successCount.get() + " (" + 
                          String.format("%.1f%%", (double) successCount.get() / totalRequests * 100) + ")");
        System.out.println("실패: " + errorCount.get() + " (" + 
                          String.format("%.1f%%", (double) errorCount.get() / totalRequests * 100) + ")");
        
        if (!instanceCounts.isEmpty()) {
            System.out.println("\n🔄 로드밸런싱 분배 결과:");
            instanceCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                            (a, b) -> b.get() - a.get()))
                    .forEach(entry -> {
                        double percentage = (double) entry.getValue().get() / successCount.get() * 100;
                        System.out.println("  " + entry.getKey() + ": " + entry.getValue().get() + 
                                         "회 (" + String.format("%.1f%%", percentage) + ")");
                    });
            
            // 로드밸런싱 효율성 평가
            double maxPercentage = instanceCounts.values().stream()
                    .mapToDouble(count -> (double) count.get() / successCount.get() * 100)
                    .max().orElse(0);
            
            String balanceQuality = maxPercentage < 40 ? "✅ 균등함" :
                                   maxPercentage < 60 ? "⚠️ 보통" : "❌ 불균등";
            System.out.println("\n📊 분배 품질: " + balanceQuality);
        }
    }
}