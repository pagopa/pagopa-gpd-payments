package it.gov.pagopa.payments.client;

public interface BlobStorageClient {

    /**
     * Saves a JSON dead-letter message to Blob Storage.
     *
     * @param json JSON content to persist
     * @param fileName blob path without file extension
     */
    void saveStringJsonToBlobStorage(String json, String fileName);
}