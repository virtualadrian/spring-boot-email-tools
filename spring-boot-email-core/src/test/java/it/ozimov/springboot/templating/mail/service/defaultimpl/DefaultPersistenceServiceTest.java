package it.ozimov.springboot.templating.mail.service.defaultimpl;

import com.google.common.collect.ImmutableList;
import it.ozimov.cirneco.hamcrest.java7.javautils.IsUUID;
import it.ozimov.mockito.helpers.captors.ResultCaptor;
import it.ozimov.springboot.templating.mail.BaseRedisTest;
import it.ozimov.springboot.templating.mail.model.EmailSchedulingData;
import it.ozimov.springboot.templating.mail.model.defaultimpl.DefaultEmailSchedulingData;
import it.ozimov.springboot.templating.mail.service.PersistenceService;
import it.ozimov.springboot.templating.mail.utils.TimeUtils;
import lombok.NonNull;
import lombok.ToString;
import org.assertj.core.api.Condition;
import org.assertj.core.api.JUnitSoftAssertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.validation.constraints.Null;
import java.io.UnsupportedEncodingException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.danhaywood.java.assertjext.Conditions.matchedBy;
import static it.ozimov.cirneco.hamcrest.java7.javautils.IsUUID.UUID;
import static it.ozimov.springboot.templating.mail.utils.DefaultEmailToMimeMessageTest.getSimpleMail;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = {BaseRedisTest.JedisContextConfiguration.class})
@SpringBootTest(webEnvironment= SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestExecutionListeners(
        mergeMode =TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS,
        listeners = {BaseRedisTest.class})
//@Transactional//(transactionManager = "transactionManager")
public class DefaultPersistenceServiceTest extends BaseRedisTest {

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Rule
    public Timeout timeout = new Timeout(3, TimeUnit.SECONDS);

    @Rule
    public final JUnitSoftAssertions assertions = new JUnitSoftAssertions();

    @SpyBean
    @Qualifier("orderingTemplate")
    private StringRedisTemplate orderingTemplate;

    @SpyBean
    @Qualifier("valueTemplate")
    private RedisTemplate<String, EmailSchedulingData> valueTemplate;

    @SpyBean
    @Qualifier("defaultEmailPersistenceService")
    private DefaultPersistenceService defaultPersistenceService;

    @Override
    public void additionalSetUp() {
//        ReflectionTestUtils.setField(defaultPersistenceService, "orderingTemplate", orderingTemplate);
//        ReflectionTestUtils.setField(defaultPersistenceService, "valueTemplate", valueTemplate);
    }

    @Captor
    private ArgumentCaptor<String> valueTemplateKeyArgumentCaptor;
    @Captor
    private ArgumentCaptor<String> orderingTemplateKeyArgumentCaptor;

    private ResultCaptor<BoundZSetOperations<String, String>> orderingTemplateBoundZSetOperationsResultCaptor;

    @Test
    public void shouldAddThrowNullPointerExceptionWhenInputParamIsNull() throws Exception {
        //Arrange
        expectedException.expect(NullPointerException.class);

        //Act
        defaultPersistenceService.add(null);
    }

    @Test
    public void shouldAddInsertNewEmailSchedulingData() throws Exception {
        //Arrange
        final int assignedPriority = 1;
        final DefaultEmailSchedulingData defaultEmailSchedulingData = createDefaultEmailSchedulingDataWithPriority(assignedPriority);

        final String expectedOrderingKey = RedisBasedPersistenceServiceConstants.orderingKey(assignedPriority);
        final String expectedValueKey = defaultEmailSchedulingData.getId();

        setBeforeTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey.getBytes())).isFalse();
        });
        setAfterTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey.getBytes()))
                    .as("After adding we should have the ordering key in REDIS")
                    .isTrue();
            assertions.assertThat(connection.exists(expectedValueKey.getBytes()))
                    .as("After adding we should have the value key in REDIS")
                    .isTrue();
        });

        //Act
        defaultPersistenceService.add(defaultEmailSchedulingData);

        //Assert
        InOrder inOrder = inOrder(orderingTemplate, valueTemplate);

        inOrder.verify(orderingTemplate).boundZSetOps(orderingTemplateKeyArgumentCaptor.capture());
        String orderingKey = orderingTemplateKeyArgumentCaptor.getValue();
        assertions.assertThat(orderingKey).isEqualTo(expectedOrderingKey);

        inOrder.verify(valueTemplate).boundValueOps(valueTemplateKeyArgumentCaptor.capture());
        String valueKey = valueTemplateKeyArgumentCaptor.getValue();
        assertions.assertThat(valueKey).is(matchedBy(UUID()));
    }

    @Test
    public void shouldAddReplaceEmailSchedulingDataWhenTheValueKeyWasAlreadySet() throws Exception {
        //Arrange
        final int assignedPriority = 1;
        final DefaultEmailSchedulingData defaultEmailSchedulingData = createDefaultEmailSchedulingDataWithPriority(assignedPriority);

        final String expectedOrderingKey = RedisBasedPersistenceServiceConstants.orderingKey(assignedPriority);
        final String expectedValueKey = defaultEmailSchedulingData.getId();

        final DefaultEmailSchedulingData defaultEmailSchedulingDataSameId =
                createDefaultEmailSchedulingDataWithPriority(assignedPriority);
        final OffsetDateTime dateTime = TimeUtils.offsetDateTimeNow().plusYears(1);
        ReflectionTestUtils.setField(defaultEmailSchedulingDataSameId, "id", expectedValueKey);
        ReflectionTestUtils.setField(defaultEmailSchedulingDataSameId, "scheduledDateTime", dateTime);


        setBeforeTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey.getBytes())).isFalse();
        });
        setAfterTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey.getBytes()))
                    .as("After adding we should have the ordering key in REDIS")
                    .isTrue();
            assertions.assertThat(connection.exists(expectedValueKey.getBytes()))
                    .as("After adding we should have the value key in REDIS")
                    .isTrue();
        });

        //Act
        defaultPersistenceService.add(defaultEmailSchedulingData);
        defaultPersistenceService.add(defaultEmailSchedulingDataSameId);

        //Assert
        Optional<EmailSchedulingData> givenOptionalEmailSchedulingData = defaultPersistenceService.get(expectedValueKey);
        assertions.assertThat(givenOptionalEmailSchedulingData)
                .as("Should not have been replaced")
                .contains(defaultEmailSchedulingDataSameId);
    }

////    @Transactional(transactionManager = "transactionManager", propagation = Propagation.REQUIRES_NEW)
////    @Rollback(false)
//    private void add(EmailSchedulingData emailSchedulingData) {
//        defaultPersistenceService.add(emailSchedulingData);
//    }

    @Test
    public void shouldGetThrowNullPointerExceptionWhenInputParamIsNull() throws Exception {
        //Arrange
        expectedException.expect(NullPointerException.class);

        //Act
        defaultPersistenceService.get(null);
    }

    @Test
    @Rollback(false)
    public void shouldGetReturnEmailSchedulingDataWhenPresent() throws Exception {
        //Arrange
        final int assignedPriority = 1;
        final DefaultEmailSchedulingData defaultEmailSchedulingData = createDefaultEmailSchedulingDataWithPriority(assignedPriority);

        defaultPersistenceService.add(defaultEmailSchedulingData);

        //Act
        Optional<EmailSchedulingData> givenOptionalEmailSchedulingData =
                defaultPersistenceService.get(defaultEmailSchedulingData.getId());

        //Assert
        assertions.assertThat(givenOptionalEmailSchedulingData)
                .isNotEmpty()
                .containsInstanceOf(DefaultEmailSchedulingData.class)
                .contains(defaultEmailSchedulingData);
    }

    @Test
    public void shouldGetReturnEmptyOptionalWhenNotPresent() throws Exception {
        //Arrange
        final String expectedValueKey = UUID.randomUUID().toString();

        setBeforeTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedValueKey.getBytes())).isFalse();
        });

        //Act
        Optional<EmailSchedulingData> givenOptionalEmailSchedulingData =
                defaultPersistenceService.get(expectedValueKey);

        //Assert
        assertions.assertThat(givenOptionalEmailSchedulingData)
                .isEmpty();
    }

    @Test
    public void shouldRemoveThrowNullPointerExceptionWhenInputParamIsNull() throws Exception {
        //Arrange
        expectedException.expect(NullPointerException.class);

        //Act
        defaultPersistenceService.remove(null);
    }

    @Test
    public void shouldRemoveReturnTrueWhenEmailSchedulingDataWasPresent() throws Exception {
        //Arrange
        final int assignedPriority = 1;
        final DefaultEmailSchedulingData defaultEmailSchedulingData = createDefaultEmailSchedulingDataWithPriority(assignedPriority);

        final String expectedOrderingKey = RedisBasedPersistenceServiceConstants.orderingKey(assignedPriority);
        final String expectedValueKey = defaultEmailSchedulingData.getId();

        setBeforeTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey.getBytes())).isFalse();
        });
        setAfterTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey.getBytes()))
                    .as("After removal we should not have the ordering key in REDIS")
                    .isTrue();
            assertions.assertThat(connection.exists(expectedValueKey.getBytes()))
                    .as("After removal we should not have the value key in REDIS")
                    .isTrue();
        });

        defaultPersistenceService.add(defaultEmailSchedulingData);

        //Act
        final boolean removed = defaultPersistenceService.remove(expectedValueKey);

        //Assert
        assertions.assertThat(removed).isTrue();

        InOrder inOrder = inOrder(orderingTemplate, valueTemplate);

        inOrder.verify(valueTemplate).delete(expectedValueKey);
        inOrder.verify(orderingTemplate).boundZSetOps(expectedOrderingKey);
    }

    @Test
    public void shouldRemoveReturnFalseWhenEmailSchedulingDataWasNotPresent() throws Exception {
        //Arrange
        final String expectedOrderingKey = RedisBasedPersistenceServiceConstants.orderingKey(1);
        final String expectedValueKey = UUID.randomUUID().toString();

        setBeforeTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey.getBytes())).isFalse();
        });

        //Act
        final boolean removed = defaultPersistenceService.remove(expectedValueKey);

        //Assert
        assertions.assertThat(removed).isFalse();
    }


    @Test
    public void shouldAddAllThrowNullPointerExceptionWhenInputParamIsNull() throws Exception {
        //Arrange
        expectedException.expect(NullPointerException.class);

        //Act
        defaultPersistenceService.addAll(null);
    }

    @Test
    public void shouldAddAllInsertCollectionOfEmailSchedulingData() throws Exception {
        //Arrange
        final int assignedPriority_1 = 1;
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_1 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_2 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_3 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);

        final int assignedPriority_2 = 2;
        assertions.assertThat(assignedPriority_1).isNotEqualTo(assignedPriority_2);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_2_1 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_2);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_2_2 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_2);

        final String expectedOrderingKey_1 = RedisBasedPersistenceServiceConstants.orderingKey(assignedPriority_1);
        final String expectedValueKey_1_1 = defaultEmailSchedulingData_1_1.getId();
        final String expectedValueKey_1_2 = defaultEmailSchedulingData_1_2.getId();
        final String expectedValueKey_1_3 = defaultEmailSchedulingData_1_3.getId();

        final String expectedOrderingKey_2 = RedisBasedPersistenceServiceConstants.orderingKey(assignedPriority_2);
        final String expectedValueKey_2_1 = defaultEmailSchedulingData_2_1.getId();
        final String expectedValueKey_2_2 = defaultEmailSchedulingData_2_2.getId();

        setBeforeTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey_1.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey_1_1.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey_1_2.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey_1_3.getBytes())).isFalse();

            assertions.assertThat(connection.exists(expectedOrderingKey_2.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey_2_1.getBytes())).isFalse();
            assertions.assertThat(connection.exists(expectedValueKey_2_2.getBytes())).isFalse();
        });
        setAfterTransactionAssertion(connection -> {
            assertions.assertThat(connection.exists(expectedOrderingKey_1.getBytes())).isTrue();
            assertions.assertThat(connection.exists(expectedValueKey_1_1.getBytes())).isTrue();
            assertions.assertThat(connection.exists(expectedValueKey_1_2.getBytes())).isTrue();
            assertions.assertThat(connection.exists(expectedValueKey_1_3.getBytes())).isTrue();

            assertions.assertThat(connection.exists(expectedOrderingKey_2.getBytes())).isTrue();
            assertions.assertThat(connection.exists(expectedValueKey_2_1.getBytes())).isTrue();
            assertions.assertThat(connection.exists(expectedValueKey_2_2.getBytes())).isTrue();
        });

        //Act
        defaultPersistenceService.addAll(ImmutableList.of(defaultEmailSchedulingData_1_1, defaultEmailSchedulingData_1_2, defaultEmailSchedulingData_1_3,
                defaultEmailSchedulingData_2_1, defaultEmailSchedulingData_2_2));

        //Assert

    }

    @Test
    public void shouldGetNextBatchForOrderingKeyReturnNothingGivenNonPositiveBatchSize() throws Exception {
        //Arrange
        int assignedPriority = 1;

        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Batch size should be a positive integer.");

        //Act
        defaultPersistenceService.getNextBatch(assignedPriority, 0);
    }

    @Test
    public void shouldGetNextBatchForOrderingKeyThrowExceptionGivenNoEmailSchedulingData() throws Exception {
        //Arrange
        int assignedPriority = 1;

        //Act
        Collection<EmailSchedulingData> givenBatch = defaultPersistenceService.getNextBatch(assignedPriority, 100);

        //Assert
        assertions.assertThat(givenBatch).isEmpty();
    }

    @Test
    public void shouldGetNextBatchForOrderingKeyReturnDesiredAmountOfEmailSchedulingData() throws Exception {
        //Arrange
        final int assignedPriority_1 = 1;
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_1 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_2 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        TimeUnit.MILLISECONDS.sleep(1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_3 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);

        final int assignedPriority_2 = 2;
        assertions.assertThat(assignedPriority_1).isNotEqualTo(assignedPriority_2);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_2_1 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_2);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_2_2 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_2);

        defaultPersistenceService.addAll(ImmutableList.of(defaultEmailSchedulingData_1_1, defaultEmailSchedulingData_1_2, defaultEmailSchedulingData_1_3,
                defaultEmailSchedulingData_2_1, defaultEmailSchedulingData_2_2));

        int desiredBatchSize = 2;

        //Act
        Collection<EmailSchedulingData> givenBatch = defaultPersistenceService.getNextBatch(assignedPriority_1, desiredBatchSize);

        //Assert
        assertions.assertThat(givenBatch)
                .hasSize(desiredBatchSize)
                .containsOnly(defaultEmailSchedulingData_1_1, defaultEmailSchedulingData_1_2);

    }

    @Test
    public void shouldGetNextBatchForOrderingKeyReturnAvailableEmailSchedulingDataWhenBatchSizeExceedAvailableEntries() throws Exception {
        //Arrange
        final int assignedPriority_1 = 1;
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_1 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_2 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        TimeUnit.MILLISECONDS.sleep(1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_3 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);

        Collection<EmailSchedulingData> emailSchedulingDataCollection = ImmutableList.of(defaultEmailSchedulingData_1_1, defaultEmailSchedulingData_1_2, defaultEmailSchedulingData_1_3);
        defaultPersistenceService.addAll(emailSchedulingDataCollection);

        int availableBatchSize = emailSchedulingDataCollection.size();
        int desiredBatchSize = availableBatchSize+2;
        assertions.assertThat(desiredBatchSize).isGreaterThan(availableBatchSize);

        //Act
        Collection<EmailSchedulingData> givenBatch = defaultPersistenceService.getNextBatch(assignedPriority_1, desiredBatchSize);

        //Assert
        assertions.assertThat(givenBatch)
                .hasSize(availableBatchSize)
                .containsOnlyElementsOf(emailSchedulingDataCollection);
    }

    @Test
    public void shouldGetNextBatchThrowExceptionGivenNonPositiveBatchSize() throws Exception {
        //Arrange
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Batch size should be a positive integer.");

        //Act
        defaultPersistenceService.getNextBatch(0);
    }

    @Test
    public void shouldGetNextBatchThrowExceptionGivenNoEmailSchedulingData() throws Exception {
        //Act
        Collection<EmailSchedulingData> givenBatch = defaultPersistenceService.getNextBatch(100);

        //Assert
        assertions.assertThat(givenBatch).isEmpty();
    }

    @Test
    public void shouldGetNextBatchReturnDesiredAmountOfEmailSchedulingData() throws Exception {
        //Arrange
        final int assignedPriority_1 = 1;
        final int assignedPriority_2 = 2;
        assertions.assertThat(assignedPriority_1).isNotEqualTo(assignedPriority_2);

        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_1 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        TimeUnit.MILLISECONDS.sleep(1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_2_1 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_2);
        TimeUnit.MILLISECONDS.sleep(1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_2_2 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_2);
        TimeUnit.MILLISECONDS.sleep(1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_2 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        TimeUnit.MILLISECONDS.sleep(1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_3 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);

        defaultPersistenceService.addAll(ImmutableList.of(defaultEmailSchedulingData_1_1, defaultEmailSchedulingData_1_2, defaultEmailSchedulingData_1_3,
                defaultEmailSchedulingData_2_1, defaultEmailSchedulingData_2_2));

        int desiredBatchSize = 3;

        //Act
        Collection<EmailSchedulingData> givenBatch = defaultPersistenceService.getNextBatch(desiredBatchSize);

        //Assert
        assertions.assertThat(givenBatch)
                .hasSize(desiredBatchSize)
                .containsOnly(defaultEmailSchedulingData_1_1, defaultEmailSchedulingData_2_1, defaultEmailSchedulingData_2_2);
    }

    @Test
    public void shouldGetNextBatchReturnAvailableEmailSchedulingDataWhenBatchSizeExceedAvailableEntries() throws Exception {
        //Arrange
        final int assignedPriority_1 = 1;
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_1 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        TimeUnit.MILLISECONDS.sleep(1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_2 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);
        TimeUnit.MILLISECONDS.sleep(1);
        final DefaultEmailSchedulingData defaultEmailSchedulingData_1_3 = createDefaultEmailSchedulingDataWithPriority(assignedPriority_1);

        Collection<EmailSchedulingData> emailSchedulingDataCollection = ImmutableList.of(defaultEmailSchedulingData_1_1, defaultEmailSchedulingData_1_2, defaultEmailSchedulingData_1_3);
        defaultPersistenceService.addAll(emailSchedulingDataCollection);

        int availableBatchSize = emailSchedulingDataCollection.size();
        int desiredBatchSize = availableBatchSize+2;
        assertions.assertThat(desiredBatchSize).isGreaterThan(availableBatchSize);

        //Act
        Collection<EmailSchedulingData> givenBatch = defaultPersistenceService.getNextBatch(desiredBatchSize);

        //Assert
        assertions.assertThat(givenBatch)
                .hasSize(availableBatchSize)
                .containsOnlyElementsOf(emailSchedulingDataCollection);
    }

    private DefaultEmailSchedulingData createDefaultEmailSchedulingDataWithPriority(final int assignedPriority) throws UnsupportedEncodingException {
        final OffsetDateTime dateTime = TimeUtils.offsetDateTimeNow();

        final DefaultEmailSchedulingData defaultEmailSchedulingData = DefaultEmailSchedulingData.defaultEmailSchedulingDataBuilder()
                .email(getSimpleMail())
                .scheduledDateTime(dateTime)
                .assignedPriority(assignedPriority)
                .desiredPriority(assignedPriority)
                .build();
        return defaultEmailSchedulingData;
    }

}