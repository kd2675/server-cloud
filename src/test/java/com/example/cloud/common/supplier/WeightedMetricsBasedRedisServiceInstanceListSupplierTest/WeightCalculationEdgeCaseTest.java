package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest.WeightedMetricsTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("가중치 계산 엣지 케이스 테스트")
class WeightCalculationEdgeCaseTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("NaN 부하점수에 대한 가중치 계산")
    void testCalculateWeightWithNaN() {
        // When
        double weight = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", Double.NaN);

        // Then - NaN의 경우 최소 가중치(1.0)가 반환되어야 함
        assertThat(weight).isEqualTo(1.0);
        assertThat(Double.isNaN(weight)).isFalse(); // NaN이 아님을 확인
    }

    @Test
    @DisplayName("양의 무한대 부하점수에 대한 가중치 계산")
    void testCalculateWeightWithPositiveInfinity() {
        // When
        double weight = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", Double.POSITIVE_INFINITY);

        // Then - 무한대의 경우 최소 가중치가 적용되어야 함
        assertThat(weight).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, 5.0, 9.99, 10.0})
    @DisplayName("10.0 미만 부하점수에서 높은 가중치 적용 확인")
    void testCalculateWeightWithLowLoadScores(double loadScore) {
        // When
        double weight = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", loadScore);

        // Then - 낮은 부하점수일수록 높은 가중치
        assertThat(weight).isGreaterThan(1.0);
        if (loadScore <= 10.0) {
            assertThat(weight).isEqualTo(10.0); // Math.max(loadScore, 10.0)에 의해 10.0 사용
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {10.01, 20.0, 50.0, 100.0})
    @DisplayName("10.0 초과 부하점수에서 역수 가중치 계산 확인")
    void testCalculateWeightWithHighLoadScores(double loadScore) {
        // When
        double weight = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", loadScore);

        // Then - 역수 방식으로 가중치 계산
        double expectedWeight = Math.max(1.0, Math.min(10.0, 100.0 / loadScore));
        assertThat(weight).isEqualTo(expectedWeight);
    }

    @Test
    @DisplayName("극값들의 가중치 계산 안정성")
    void testCalculateWeightStabilityWithExtremeValues() {
        double[] extremeValues = {
            Double.MIN_VALUE, Double.MAX_VALUE, 
            -Double.MAX_VALUE, 1e-10, 1e10,
            0.0001, 9999.9999
        };

        for (double loadScore : extremeValues) {
            // When
            double weight = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", loadScore);

            // Then - 모든 경우에 유효한 범위 내의 가중치
            assertThat(weight)
                .describedAs("LoadScore: %f should produce valid weight", loadScore)
                .isGreaterThanOrEqualTo(1.0)
                .isLessThanOrEqualTo(10.0)
                .isFinite(); // NaN이나 Infinity가 아님을 확인
        }
    }

    @Test
    @DisplayName("가중치 계산 결과의 재현성 확인")
    void testCalculateWeightConsistency() {
        double[] testScores = {15.0, 25.0, 50.0, 75.0};
        
        for (double score : testScores) {
            // When - 같은 입력으로 여러 번 계산
            double weight1 = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", score);
            double weight2 = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", score);
            double weight3 = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", score);

            // Then - 항상 동일한 결과
            assertThat(weight1).isEqualTo(weight2).isEqualTo(weight3);
        }
    }
}