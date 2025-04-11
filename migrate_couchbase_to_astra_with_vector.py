# migrate_couchbase_to_astra_with_vector.py
import json
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import timedelta
import collections
from dotenv import load_dotenv

# Using astrapy now
from astrapy import DataAPIClient
from astrapy.constants import VectorMetric
from astrapy.ids import UUID
from astrapy.info import CollectionDefinition
# Import exception from its submodule - Removed as it doesn't exist
# from astrapy.exceptions import CollectionNotFound

# Re-add Couchbase SDK imports
from couchbase.auth import PasswordAuthenticator
from couchbase.cluster import Cluster
from couchbase.exceptions import CouchbaseException, DocumentNotFoundException
from couchbase.options import ClusterOptions, QueryOptions

# --- Configuration ---
load_dotenv()

# Astra DB Configuration
ASTRA_API_ENDPOINT = os.getenv("ASTRA_DB_API_ENDPOINT")
ASTRA_APPLICATION_TOKEN = os.getenv("ASTRA_DB_APPLICATION_TOKEN")
ASTRA_KEYSPACE = os.getenv("ASTRA_DB_KEYSPACE", "default_keyspace")
ASTRA_COLLECTION_PER_TYPE = True # Assuming one collection per type

# Couchbase Configuration
CB_CONNECT_STRING = "couchbase://localhost"
CB_BUCKET_NAME = "travel-sample"
CB_USERNAME = "Administrator"
CB_PASSWORD = "password"
CB_QUERY_TIMEOUT = timedelta(seconds=120)

# Migration Configuration
# Ensure hotel is included, user and booking will be handled separately
# DOCUMENT_TYPES_TO_MIGRATE = ["hotel"] # Focusing on hotel as per previous steps, adjust if needed
# Process all relevant types for the travel-sample application
DOCUMENT_TYPES_TO_MIGRATE = ["airline", "airport", "route", "landmark", "hotel", "user", "booking"]

# Vector configuration (Constants needed for programmatic creation attempt)
VECTOR_DIMENSION = 384 # Match dimension expected by $vectorize service (e.g., all-MiniLM-L6-v2)
VECTOR_METRIC = VectorMetric.COSINE # Use Enum for builder syntax
VECTOR_SOURCE_FIELDS = ["name", "description", "address", "city", "state", "country"] # Used for $vectorize

# Other configs
CONCURRENCY = 1 # Set low for debugging/embedding generation
LOG_LEVEL = logging.INFO
# Removed DROP_COLLECTIONS_BEFORE_MIGRATION flag
# DROP_COLLECTIONS_BEFORE_MIGRATION = False

logging.basicConfig(level=LOG_LEVEL, format='%(asctime)s - %(levelname)s - %(message)s')

# --- Astra DB Client Initialization ---
astra_client = None
astra_db = None
try:
    logging.info("Initializing DataAPIClient...")
    astra_client = DataAPIClient(ASTRA_APPLICATION_TOKEN)
    astra_db = astra_client.get_database(ASTRA_API_ENDPOINT, keyspace=ASTRA_KEYSPACE)
    logging.info(f"DataAPIClient connected to database at {ASTRA_API_ENDPOINT}, targeting keyspace '{ASTRA_KEYSPACE}'.")
except Exception as e:
    logging.critical(f"Failed to initialize DataAPIClient or connect to database: {e}", exc_info=True)
    exit(1)

# --- Astra DB Functions ---

def ensure_astra_collection(collection_name: str, vector_enabled: bool = False):
    """Ensures an Astra collection exists. Deletes it first, then creates it.
       Attempts programmatic creation for both vector and non-vector types.
       WARNING: Programmatic vector creation with DataAPIClient may fail.
    """
    global astra_db

    # 1. Attempt to delete the collection first
    try:
        logging.warning(f"Attempting to delete collection '{collection_name}' before creation...")
        astra_db.delete_collection(collection_name=collection_name)
        logging.info(f"Collection '{collection_name}' deleted successfully (or did not exist)." )
    except Exception as e_delete:
        # Log error but proceed with creation attempt
        logging.error(f"Error attempting to delete collection '{collection_name}': {e_delete}. Proceeding with creation attempt.")

    # 2. Attempt to create the collection
    try:
        if vector_enabled:
            # Attempt creation using CollectionDefinition builder
            logging.info(f"Attempting to create vector-enabled collection '{collection_name}' using builder (Dim: {VECTOR_DIMENSION}, Metric: {VECTOR_METRIC})...")
            collection = astra_db.create_collection(
                collection_name,
                definition=(
                    CollectionDefinition.builder()
                    .set_vector_service(
                         provider="huggingface",
                         model_name="sentence-transformers/all-MiniLM-L6-v2", # Ensure this matches VECTOR_DIMENSION
                         authentication={"providerKey": "huggingface"} # Optional authentication
                     )
                    .build()
                )
            )
            logging.info(f"Successfully created vector collection '{collection_name}' using builder.")
            return collection
        else:
            # Attempt standard creation
            logging.info(f"Attempting to create standard collection '{collection_name}'...")
            collection = astra_db.create_collection(collection_name)
            logging.info(f"Successfully created standard collection '{collection_name}'.")
            return collection

    except Exception as e_create:
        logging.error(f"Failed to create collection '{collection_name}': {e_create}", exc_info=True)
        if vector_enabled:
            logging.error("Programmatic vector collection creation failed. You may need to create it manually via UI/CLI.")
        return None


def insert_batch_astra(astra_collection, documents: list, collection_name_for_log: str):
    """Inserts a batch of documents into a specific Astra collection using astrapy."""
    if not documents:
        return 0
    if not astra_collection:
        logging.error("Invalid Collection object provided to insert_batch_astra.")
        return 0

    try:
        logging.info(f"Attempting insert_many for {len(documents)} docs into '{collection_name_for_log}'...")
        result = astra_collection.insert_many(documents)
        inserted_count = len(result.inserted_ids)
        logging.debug(f"Astrapy insert_many successful for {inserted_count} docs into '{collection_name_for_log}'.")
        if inserted_count < len(documents):
             logging.warning(f"Astrapy insert_many might have partially failed for collection '{collection_name_for_log}'. Expected {len(documents)}, got {inserted_count} IDs.")
        return inserted_count
    except Exception as e:
        logging.error(f"Unexpected error during astrapy insert_many in collection '{collection_name_for_log}': {e}", exc_info=True)
        try:
            failing_ids = [doc.get('_id', 'unknown') for doc in documents]
            logging.error(f"Failing document IDs (attempted batch): {failing_ids}")
        except Exception as log_e:
            logging.error(f"Error logging failing document IDs: {log_e}")
        return 0


# --- Couchbase Functions ---
def connect_couchbase():
    """Connects to the Couchbase cluster and opens the bucket."""
    logging.info(f"Connecting to Couchbase cluster at {CB_CONNECT_STRING}...")
    try:
        auth = PasswordAuthenticator(CB_USERNAME, CB_PASSWORD)
        options = ClusterOptions(auth)
        cluster = Cluster(CB_CONNECT_STRING, options)
        cluster.wait_until_ready(timedelta(seconds=30))
        bucket = cluster.bucket(CB_BUCKET_NAME)
        coll = bucket.default_collection()
        logging.info(f"Connected to Couchbase bucket '{CB_BUCKET_NAME}'.")
        return cluster, bucket, coll
    except CouchbaseException as e:
        logging.error(f"Failed to connect to Couchbase: {e}")
        raise

def get_document_keys_by_type(cluster, doc_type: str):
    """Gets all document keys for a specific type from Couchbase."""
    logging.info(f"Fetching document keys for type: {doc_type}...")
    query = f'SELECT RAW META().id FROM `{CB_BUCKET_NAME}` WHERE type = $1'
    try:
        result = cluster.query(query, QueryOptions(positional_parameters=[doc_type], timeout=CB_QUERY_TIMEOUT))
        keys = [row for row in result]
        logging.info(f"Found {len(keys)} keys for type '{doc_type}'.")
        return keys
    except CouchbaseException as e:
        logging.error(f"Failed to query keys for type '{doc_type}': {e}")
        return []

# --- Modified Document Content Fetching ---
def get_document_content(cb_collection, key: str):
    """Fetches the content of a single document from Couchbase, prepares it for Astra,
       and adds a $vectorize field for hotel documents."""
    try:
        result = cb_collection.get(key)
        original_doc = result.content_as[dict]
        doc_type = original_doc.get('type')

        migrated_doc = {'_id': key}
        text_for_vectorize = []

        for field_key, field_value in original_doc.items():
            # Skip Couchbase CAS field
            if field_key == 'cas':
                continue

            # Handle 'content' field specifically for hotels - skip
            # Also skip '_id' as we set it manually
            if field_key == '_id' or (doc_type == 'hotel' and field_key == 'content'):
                continue

            # Copy field
            migrated_doc[field_key] = field_value

            # Collect text fields for hotel vectorization
            if doc_type == 'hotel' and isinstance(field_value, str) and field_key in ['name', 'description', 'address', 'city', 'state', 'country']:
                text_for_vectorize.append(field_value)

        # Add $vectorize field for hotels if text was collected
        if doc_type == 'hotel':
            if text_for_vectorize:
                combined_text = " ".join(filter(None, text_for_vectorize)) # Join non-null strings
                if combined_text:
                    migrated_doc['$vectorize'] = combined_text
                    logging.debug(f"Added '$vectorize' field for hotel '{key}' with text: \"{combined_text[:50]}...\"")
                else:
                    logging.warning(f"Combined text for vectorization was empty for hotel '{key}'.")
            else:
                logging.warning(f"No text fields found to add '$vectorize' for hotel '{key}'.")

        return migrated_doc

    except DocumentNotFoundException:
        logging.warning(f"Document key not found during fetch: {key}")
        return None
    except CouchbaseException as e:
        logging.error(f"Failed to fetch document '{key}': {e}")
        return None

# --- Migration Logic ---

def process_document_batch(cb_collection, keys_batch: list, astra_collection, collection_name_for_log: str):
    """Fetches a batch of documents from CB and inserts into Astra using astrapy."""
    astra_docs = []
    successful_fetch_count = 0
    for key in keys_batch:
        content = get_document_content(cb_collection, key)
        if content:
            astra_docs.append(content)
            successful_fetch_count += 1

    if not astra_docs:
        logging.warning(f"No documents successfully fetched for batch intended for collection '{collection_name_for_log}'.")
        return 0, 0 # Fetched, Inserted

    inserted_count = insert_batch_astra(astra_collection, astra_docs, collection_name_for_log)
    return successful_fetch_count, inserted_count

def migrate_type(cb_cluster, cb_collection, doc_type: str):
    """Migrates all documents of a specific type using astrapy."""
    logging.info(f"--- Starting migration for type: {doc_type} ---")
    start_time = time.time()

    # Ensure the Astra collection exists and get the object
    # Enable vector for 'hotel' type
    is_vector = (doc_type == 'hotel')
    astra_collection = ensure_astra_collection(doc_type, vector_enabled=is_vector)
    if not astra_collection:
        logging.error(f"Failed to get or create Astra collection '{doc_type}'. Skipping migration for this type.")
        return 0, 0 # Indicate failure

    keys = get_document_keys_by_type(cb_cluster, doc_type)

    if not keys:
        logging.warning(f"No keys found for type '{doc_type}'. Skipping insertion phase.")
        return 0, 0

    total_keys = len(keys)
    total_fetched = 0
    total_inserted = 0

    # Process keys in batches using a thread pool
    CB_FETCH_BATCH_SIZE = 50 # Reduce batch size slightly due to embedding overhead

    with ThreadPoolExecutor(max_workers=CONCURRENCY) as executor:
        futures = []
        for i in range(0, total_keys, CB_FETCH_BATCH_SIZE):
            batch_keys = keys[i:i + CB_FETCH_BATCH_SIZE]
            futures.append(executor.submit(process_document_batch, cb_collection, batch_keys, astra_collection, doc_type))

        processed_batches = 0
        processed_keys_estimate = 0
        for future in as_completed(futures):
            try:
                fetched, inserted = future.result()
                total_fetched += fetched
                total_inserted += inserted
                processed_batches += 1
                processed_keys_estimate = processed_batches * CB_FETCH_BATCH_SIZE # Rough estimate
                if processed_batches % 10 == 0: # Log progress periodically based on CB fetch batches
                     logging.info(f"[{doc_type}] Progress: Processed ~{processed_keys_estimate}/{total_keys} keys. Fetched: {total_fetched}, Inserted: {total_inserted}")

            except Exception as e:
                logging.error(f"Error processing batch future for type '{doc_type}': {e}", exc_info=True)

    end_time = time.time()
    duration = end_time - start_time
    logging.info(f"--- Finished migration for type: {doc_type} ---")
    logging.info(f"[{doc_type}] Total Keys: {total_keys}")
    logging.info(f"[{doc_type}] Successfully Fetched: {total_fetched}")
    logging.info(f"[{doc_type}] Successfully Inserted: {total_inserted}")
    logging.info(f"[{doc_type}] Duration: {duration:.2f} seconds")
    return total_fetched, total_inserted

# --- Main Execution ---

def main():
    """Main migration process orchestration."""
    logging.info("=== Starting Couchbase to Astra DB Migration with Vector ===")
    overall_start_time = time.time()

    cb_cluster = None
    cb_collection = None

    try:
        # Connect to Couchbase
        cb_cluster, _, cb_collection = connect_couchbase()

        # Ensure user and booking collections exist (non-vector)
        # ensure_astra_collection("user", vector_enabled=False)
        # ensure_astra_collection("booking", vector_enabled=False)

        # Migrate specified document types
        # Migrate specified document types - Only Hotel for now
        grand_total_fetched = 0
        grand_total_inserted = 0
        for doc_type in DOCUMENT_TYPES_TO_MIGRATE:
            fetched, inserted = migrate_type(cb_cluster, cb_collection, doc_type)
            grand_total_fetched += fetched
            grand_total_inserted += inserted

        logging.info("=== Migration Summary ===")
        logging.info(f"Document Types Processed: {DOCUMENT_TYPES_TO_MIGRATE}")
        logging.info(f"Grand Total Documents Fetched from Couchbase: {grand_total_fetched}")
        logging.info(f"Grand Total Documents Inserted into Astra DB: {grand_total_inserted}")

    except Exception as e:
        logging.critical(f"Migration process failed with an unexpected error: {e}", exc_info=True)
    finally:
        # Cleanup Couchbase connection if it was established
        if 'cb_cluster' in locals() and cb_cluster:
            logging.info("Disconnecting from Couchbase cluster...")
            # cb_cluster.disconnect() # Incorrect method, remove
            # Connection cleanup is often implicit or handled differently
            logging.info("Couchbase connection cleanup (implicit)...")
        logging.info("Migration script finished.")

        overall_end_time = time.time()
        overall_duration = overall_end_time - overall_start_time
        logging.info(f"Total Migration Duration: {overall_duration:.2f} seconds")
        logging.info("=== Migration Process Finished ===")

if __name__ == "__main__":
    main() 