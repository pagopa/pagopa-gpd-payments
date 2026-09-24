package it.gov.pagopa.payments.client;

import java.util.List;

public interface BlobStorageClient {

    /**
     * Saves a JSON dead-letter message to Blob Storage.
     *
     * @param json JSON content to persist
     * @param fileName blob path without file extension
     */
    void saveStringJsonToBlobStorage(String json, String fileName);

    /**
     * Returns the names of JSON blobs stored in dead-letter storage.
     *
     * @param maxMessages maximum number of blob names to return
     * @return blob names including the .json extension
     */
    List<String> listJsonBlobs(int maxMessages);

    /**
     * Reads the JSON content of a dead-letter blob.
     *
     * @param fileName full blob name including the .json extension
     * @return blob content as UTF-8 string
     */
    String getStringJsonFromBlobStorage(String fileName);
}