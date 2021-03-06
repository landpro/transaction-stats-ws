package pro.landlabs.transaction.stats.ws;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import pro.landlabs.transaction.stats.App;
import pro.landlabs.transaction.stats.test.TransactionMother;
import pro.landlabs.transaction.stats.ws.value.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static pro.landlabs.transaction.stats.App.STATS_PERIOD_SECONDS;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = App.class)
@WebAppConfiguration
public class TransactionStatisticsControllerTest {

    public static final double DOUBLE_PRECISION = 0.0001;

    private MediaType contentType = new MediaType(MediaType.APPLICATION_JSON.getType(),
            MediaType.APPLICATION_JSON.getSubtype(),
            Charset.forName("utf8"));

    @Autowired
    private WebApplicationContext webApplicationContext;

    private HttpMessageConverter mappingJackson2HttpMessageConverter;

    private MockMvc mockMvc;

    @Autowired
    void setConverters(HttpMessageConverter<?>[] converters) {
        this.mappingJackson2HttpMessageConverter = Arrays.stream(converters)
                .filter(converter -> converter instanceof MappingJackson2HttpMessageConverter)
                .findAny()
                .orElse(null);

        assertThat(mappingJackson2HttpMessageConverter, notNullValue());
    }

    @Before
    public void setUp() {
        this.mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    @Test
    public void verifyEmptyStatisticsOutput() throws Exception {
        mockMvc.perform(get("/statistics")
                .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"sum\": 0,\n" +
                                "\"avg\": 0,\n" +
                                "\"max\": 0,\n" +
                                "\"min\": 0,\n" +
                                "\"count\": 0}"
                ));
    }

    @Test
    @DirtiesContext
    public void shouldProduceStatisticsForMultipleTransactionsSkippingOutOfRangeTransactions() throws Exception {
        // given
        DateTime currentDateTime = DateTime.now();

        List<Transaction> transactions = ImmutableList.of(
                TransactionMother.createTransaction(randomSecondsBackWithinRange(currentDateTime), 12.3),
                TransactionMother.createTransaction(randomSecondsBackWithinRange(currentDateTime)),
                TransactionMother.createTransaction(randomSecondsBackWithinRange(currentDateTime))
        );
        List<Transaction> outOfRangeTransactions = ImmutableList.of(
                TransactionMother.createTransaction(randomSecondsBackOtOfRange(currentDateTime)),
                TransactionMother.createTransaction(randomSecondsBackOtOfRange(currentDateTime)),
                TransactionMother.createTransaction(randomSecondsBackOtOfRange(currentDateTime))
        );
        List<Transaction> allTransactions = Lists.newArrayList();
        allTransactions.addAll(transactions);
        allTransactions.addAll(outOfRangeTransactions);
        Collections.shuffle(allTransactions);

        for (Transaction transaction : allTransactions) {
            registerTransaction(transaction);
        }

        // when then
        DoubleSummaryStatistics summaryStatistics =
                transactions.stream().collect(Collectors.summarizingDouble(Transaction::getAmount));

        mockMvc.perform(get("/statistics")
                .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("sum", closeTo(round(summaryStatistics.getSum()), DOUBLE_PRECISION)))
                .andExpect(jsonPath("min", closeTo(round(summaryStatistics.getMin()), DOUBLE_PRECISION)))
                .andExpect(jsonPath("max", closeTo(round(summaryStatistics.getMax()), DOUBLE_PRECISION)))
                .andExpect(jsonPath("avg", closeTo(round(summaryStatistics.getAverage()), DOUBLE_PRECISION)))
                .andExpect(jsonPath("count", equalTo(transactions.size())));
    }

    private static double round(double value) {
        BigDecimal bd = new BigDecimal(Double.toString(value));
        bd = bd.setScale(3, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private DateTime randomSecondsBackWithinRange(DateTime currentDateTime) {
        return currentDateTime.minusSeconds(randomSecondsWithinRange());
    }

    private DateTime randomSecondsBackOtOfRange(DateTime currentDateTime) {
        return currentDateTime.minusSeconds(STATS_PERIOD_SECONDS + 1 + randomSecondsWithinRange());
    }

    private int randomSecondsWithinRange() {
        return new Random().nextInt(STATS_PERIOD_SECONDS);
    }

    private void registerTransaction(Transaction transaction) throws Exception {
        int status = mockMvc.perform(post("/transactions")
                .contentType(contentType)
                .content(json(transaction)))
                .andReturn().getResponse().getStatus();

        assertThat(status, anyOf(
                equalTo(HttpStatus.CREATED.value()), equalTo(HttpStatus.NO_CONTENT.value())
        ));
    }

    protected String json(Object o) throws Exception {
        MockHttpOutputMessage mockHttpOutputMessage = new MockHttpOutputMessage();
        mappingJackson2HttpMessageConverter.write(o, MediaType.APPLICATION_JSON, mockHttpOutputMessage);
        return mockHttpOutputMessage.getBodyAsString();
    }

}
