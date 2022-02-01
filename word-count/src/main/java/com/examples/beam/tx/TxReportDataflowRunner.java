package com.examples.beam.tx;

import com.examples.beam.core.model.EligibilityStatus;
import com.examples.beam.tx.functions.AggregateFunction;
import com.examples.beam.tx.functions.EligibilityFunction;
import com.examples.beam.tx.functions.TxStringParserFunction;
import com.examples.beam.tx.model.Transaction;
import com.examples.beam.tx.model.TxReport;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.*;

import static org.apache.beam.sdk.values.TypeDescriptors.strings;

@Slf4j
public class TxReportDataflowRunner {

    public static void main(String[] args) {
        PipelineOptionsFactory.register(TxJobOptions.class);
        TxJobOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(TxJobOptions.class);
        Pipeline pipeline = Pipeline.create(options);

        PCollectionTuple ingestedData = pipeline
                .apply("Read input", TextIO.read().from(options.getTransactionFile()))
                .apply("Parse CSV data", parseData());

        PCollectionTuple eligibilityResult = ingestedData.get(TagProvider.INGESTION_SUCCESS_TAG)
                .apply("Do Eligibility Check", ParDo.of(new EligibilityFunction()).withOutputTags(TagProvider.ELIGIBLE, TupleTagList.of(TagProvider.NOT_ELIGIBLE)));

        eligibilityResult.get(TagProvider.NOT_ELIGIBLE)
                .apply("Filter Not eligible", Filter.by((SerializableFunction<Transaction, Boolean>) auditableRecord -> !auditableRecord.getEligibilityStatus().isEligible()))
                .apply("Extract Eligibility Status", ParDo.of(new DoFn<Transaction, EligibilityStatus>() {
                    @ProcessElement
                    public void map(ProcessContext context){
                        log.info("Converting to eligibility status");
                        EligibilityStatus eligibilityStatus = context.element().getEligibilityStatus();
                        context.output(eligibilityStatus);
                    }
                }))
                .apply("Convert NE to String", MapElements.into(strings()).via(String::valueOf))
                .apply("Write NE Output", TextIO.write().to(options.getEligibilityResultFile()).withoutSharding());

        eligibilityResult.get(TagProvider.ELIGIBLE)
                .apply("Filter Eligible", Filter.by((SerializableFunction<Transaction, Boolean>) auditableRecord -> auditableRecord.getEligibilityStatus().isEligible()))
                .apply("Map By ISIN", MapElements.via(new SimpleFunction<Transaction, KV<String, Transaction>>() {
                    public KV<String, Transaction> apply(Transaction transaction) {
                        return KV.of(transaction.getIsin(), transaction);
                    }
                }))
                .apply("Group By ISIN", GroupByKey.create())
                .apply("Aggregate" , ParDo.of(new AggregateFunction()))
                .apply("Convert to String", MapElements.into(strings()).via(String::valueOf))
                .apply("Write Output", TextIO.write().to(options.getTransactionReport()).withoutSharding());



        try {
            pipeline.run().waitUntilFinish();
        }catch(UnsupportedOperationException e) {
            // do nothing
        }
    }

    private static ParDo.MultiOutput<String, Transaction> parseData() {
        return ParDo.of(new TxStringParserFunction()).withOutputTags(TagProvider.INGESTION_SUCCESS_TAG, TagProvider.INGESTION_FAILURE_TAGS);
    }
}
