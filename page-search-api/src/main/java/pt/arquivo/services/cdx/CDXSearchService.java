package pt.arquivo.services.cdx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pt.arquivo.services.*;
import org.springframework.beans.factory.annotation.Value;
import pt.arquivo.services.nutchwax.NutchWaxSearchService;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

public class CDXSearchService {

    private static final Log LOG = LogFactory.getLog(CDXSearchService.class);

    private final String equalOP = "=";
    private final String andOP = "&";
    private final String outputCDX = "json";
    private final String keyUrl = "url";
    private final String keyDigest = "digest";
    private final String keyMimeType = "mime";

    @Autowired
    private SearchService searchService;

    @Value("${searchpages.api.globaltimeout.ms}")
    private int timeoutreadConn;

    @Value("${wayback.service.cdx.timeout}")
    private int timeoutConn;

    @Value("${wayback.service.cdx.endpoint}")
    private String waybackCdxEndpoint;

    @Value("${screenshot.service.endpoint}")
    private String screenshotServiceEndpoint;

    @Value("${wayback.service.endpoint}")
    private String waybackServiceEndpoint;

    @Value("${wayback.noframe.service.endpoint}")
    private String waybackNoFrameServiceEndpoint;

    @Value("${searchpages.extractedtext.service.link}")
    private String extractedTextServiceEndpoint;

    public SearchResults getResults(String url, String from, String to, int limitP, int start) {
        Gson gson = new Gson();
        SearchResults searchResultsResponse = new SearchResults();

        ArrayList<ItemCDX> cdxResults = new ArrayList<>();
        ArrayList<SearchResult> searchResults = new ArrayList<>();

        String urlCDX = generateCdxQuery(url, from, to);
        int counter = 0;
        int limit = 0;
        if (limitP > 0) {
            limit = limitP;
        }

        LOG.info("[getResults] CDX-API URL[" + urlCDX + "]");
        try {
            List<JsonObject> jsonValues = readJsonFromUrl(urlCDX);
            if (jsonValues == null)
                return null;
            if (limit > 0)
                limit = limit + start;

            for (int i = 0; i < jsonValues.size(); i++) { //convert cdx result into object
                if (counter < start) {
                    counter++;
                    continue;
                }

                cdxResults.add(gson.fromJson(jsonValues.get(i), ItemCDX.class));
            }

            // searchResults.setResults(results);
            // check if we can get more information through the TextSearch API
            for (ItemCDX result : cdxResults){
                SearchResultImpl searchResult = new SearchResultImpl();
               // text search api
                SearchQuery urlSearchQuery = new SearchQueryImpl(result.getUrl());
                urlSearchQuery.setLimit(1);
                urlSearchQuery.setFrom(result.getTimestamp());
                urlSearchQuery.setTo(result.getTimestamp());

                SearchResults textSearchResults = searchService.query(urlSearchQuery, true);
                if (textSearchResults.getNumberResults() > 0){
                    LOG.debug("CDX record matched with full-text index.." +  result.getUrl());
                    SearchResultImpl searchResultText = (SearchResultImpl) textSearchResults.getResults().get(0);
                    searchResult.setTitle(searchResultText.getTitle());
                    searchResult.setCollection(searchResultText.getCollection());
                    searchResult.setEncoding(searchResultText.getEncoding());
                    populateEndpointsLinks(searchResult, true);
                }
                else {
                    searchResult.setTitle(result.getUrl());
                    populateEndpointsLinks(searchResult, false);
                }
                searchResult.setFileName(result.getFilename());
                searchResult.setOffset(Long.parseLong(result.getOffset()));
                // TODO SANITY CHECK HERE with the digest
                searchResult.setDigest(result.getDigest());
                searchResult.setMimeType(result.getMime());
                searchResults.add(searchResult);
            }
            return searchResultsResponse;

        } catch (Exception e) {
            LOG.debug("[getResults] URL[" + urlCDX + "] e ", e);
            return null;
        }
    }

    private String generateCdxQuery(String url, String from, String to) {
        if (from == null) {
            from = "";
        }
        if (to == null){
            to = "";
        }

        LOG.info("[CDXParser][getLink] url[" + url + "] from[" + from + "] to[" + to + "]");
        String urlEncoded = "";
        try {
            // FIX THIS encode or escape? xD
            urlEncoded = URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException un) {
            LOG.error(un);
            urlEncoded = url;
        }
        LOG.info("[cdxparser] " + this.waybackCdxEndpoint);
        StringBuilder strCdxQuery = new StringBuilder();
        strCdxQuery.append(this.waybackCdxEndpoint)
                .append("?url")
                .append(equalOP)
                .append(urlEncoded)
                .append(andOP)
                .append("output")
                .append(equalOP)
                .append(outputCDX)
                .append(andOP)
                .append("from")
                .append(equalOP)
                .append(from)
                .append(andOP)
                .append("to")
                .append(equalOP)
                .append(to)
                .append(andOP)
                .append("reverse")
                .append(equalOP)
                .append("true");

        return strCdxQuery.toString();
    }


    /**
     * Connect and get response to the CDXServer
     *
     * @param strurl
     * @return
     */
    private ArrayList<JsonObject> readJsonFromUrl(String strurl) {
        InputStream is = null;
        ArrayList<JsonObject> jsonResponse = new ArrayList<JsonObject>();

        try {
            LOG.debug("[OPEN Connection]: " + strurl);
            URL url = new URL(strurl);
            URLConnection con;
            if (strurl.startsWith("https")) {
                con = (HttpsURLConnection) url.openConnection();
            } else {
                con = url.openConnection();
            }
            con.setConnectTimeout(timeoutConn);

            // set this to a globaltimeout equal to all services
            con.setReadTimeout(timeoutreadConn);

            is = con.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            jsonResponse = readAll(rd);
            return jsonResponse;
        } catch (Exception e) {
            LOG.error("[readJsonFromUrl]" + e);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e1) {
                    LOG.error("[readJsonFromUrl] Close Stream: " + e1);
                }
            }
        }
    }

    /**
     * build json struture with CDXServer response
     *
     * @param rd
     * @return
     * @throws IOException
     * @throws ParseException
     */
    private ArrayList<JsonObject> readAll(BufferedReader rd) throws IOException {
        ArrayList<JsonObject> json = new ArrayList<JsonObject>();
        String line;
        while ((line = rd.readLine()) != null) {
            LOG.debug("[JSON LINE] : " + line);
            JsonParser parser = new JsonParser();
            JsonObject o = parser.parse(line.trim()).getAsJsonObject();
            json.add(o);
        }
        return json;
    }

    private void populateEndpointsLinks(SearchResultImpl searchResult, boolean textMatch) {
        searchResult.setLinkToArchive(waybackServiceEndpoint +
                "/" + searchResult.getTstamp() +
                "/" + searchResult.getOriginalURL());

        searchResult.setLinkToScreenshot(screenshotServiceEndpoint +
                "?url=" + searchResult.getLinkToArchive());

        searchResult.setLinkToNoFrame(waybackNoFrameServiceEndpoint +
                "/" + searchResult.getTstamp() +
                "/" + searchResult.getOriginalURL());

        if (textMatch){
            searchResult.setLinkToExtractedText(extractedTextServiceEndpoint +
                    "?m=" + searchResult.getTstamp() +
                    "/" + searchResult.getOriginalURL());
        }
    }
}