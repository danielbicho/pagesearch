package pt.arquivo.indexer.mapreduce;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.ArchiveRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.arquivo.indexer.data.PageData;
import pt.arquivo.indexer.data.WebArchiveKey;
import pt.arquivo.indexer.parsers.WARCParser;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;

public class PageSearchDataMapper extends Mapper<LongWritable, Text, WebArchiveKey, PageData> {
    // maps from an ArchiveRecord to a intermediate format
    private final Logger logger = LoggerFactory.getLogger(PageSearchDataMapper.class);
    private WARCParser warcParser;
    private int graphTimeSlice;

    enum PagesCounters {
        WARCS_COUNT,
        WARCS_DOWNLOAD_ERROR,
        RECORDS_COUNT,
        RECORDS_ACCEPTED_COUNT,
        RECORDS_DISCARDED_COUNT,
        RECORDS_DISCARDED_ERROR_COUNT
    }


    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        Config conf = ConfigFactory.load();
        this.warcParser = new WARCParser(conf);
        String graphTimePolicy = context.getConfiguration().get("GraphTimeSlice", "none");
        this.graphTimeSlice = WebArchiveKey.keyTimeSlice(graphTimePolicy);
        super.setup(context);
    }

    @Override
    protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
        String arcURL = value.toString();
        if (!arcURL.isEmpty()) {
            logger.info("(W)ARCNAME: " + arcURL);
            context.getCounter(PagesCounters.WARCS_COUNT).increment(1);

            URL url = null;
            try {
                url = new URL(arcURL);
            } catch (MalformedURLException ignored) {

            }

            String[] surl = url.getPath().split("/");
            String warcName = surl[surl.length - 1];
            String filename = System.currentTimeMillis() + "_" + warcName;
            File dest = new File("/tmp/" + filename);

            try {
                FileUtils.copyURLToFile(url, dest);
            } catch (IOException e) {
                context.getCounter(PagesCounters.WARCS_DOWNLOAD_ERROR).increment(1);
            }

            ArchiveReader reader;
            ArrayList<PageData> listDocs = new ArrayList();
            try {
                reader = ArchiveReaderFactory.get(dest);
                Iterator<ArchiveRecord> ir = reader.iterator();

                int recordCount = 1;
                int lastFailedRecord = 0;

                while (ir.hasNext()) {
                    try {
                        ArchiveRecord rec = ir.next();
                        context.getCounter(PagesCounters.RECORDS_COUNT).increment(1);
                        try {
                            PageData doc = warcParser.extract(warcName, rec);
                            if (doc != null) {
                                logger.info("Processing Record with URL: ".concat(doc.getUrl()));
                                doc.setCollection(context.getConfiguration().get("collection", ""));
                                listDocs.add(doc);
                                context.getCounter(PagesCounters.RECORDS_ACCEPTED_COUNT).increment(1);
                            } else {
                                context.getCounter(PagesCounters.RECORDS_DISCARDED_COUNT).increment(1);
                            }
                        } catch (Exception e) {
                            logger.error("Unable to parse record due to unknow reasons", e);
                            context.getCounter(PagesCounters.RECORDS_DISCARDED_ERROR_COUNT).increment(1);
                        } catch (OutOfMemoryError e) {
                            context.getCounter(PagesCounters.RECORDS_DISCARDED_ERROR_COUNT).increment(1);
                            logger.error("Unable to parse record due to out of memory problems", e);
                            continue;
                        }
                    } catch (RuntimeException e) {
                        if (lastFailedRecord != recordCount) {
                            lastFailedRecord = recordCount;
                            continue;
                        }
                        logger.error("Failed to reach next record, last record already on error - skipping the rest of the records");
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            for (PageData doc : listDocs) {
                WebArchiveKey webArchiveKey = new WebArchiveKey(doc.getUrl(), doc.getTstamp().substring(0, graphTimeSlice));
                context.write(webArchiveKey, doc);
            }

            FileUtils.deleteQuietly(dest);
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        super.cleanup(context);
    }

    @Override
    public void run(Context context) throws IOException, InterruptedException {
        super.run(context);
    }
}
