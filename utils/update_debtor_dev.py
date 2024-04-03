from azure.data.tables import TableServiceClient, UpdateMode
import argparse

'''
Useful to update debtor-fiscal-code DEV purpose
'''

parser = argparse.ArgumentParser()

parser.add_argument('--connection_string', type=str, required=True, help='Storage Connection String')
parser.add_argument('--debtor', type=str, required=True, help='Debtor to update')
parser.add_argument('--new_debtor', type=str, required=True, help='New value for debtor')
parser.add_argument('--table', type=str, required=True, help='Table name')

args = parser.parse_args()

connection_string = args.connection_string
table_service_client = TableServiceClient.from_connection_string(connection_string)
table_name = args.table

print(f"debtor:{args.debtor};")
print(f"new-debtor:{args.new_debtor};")
print(f"table-name:{args.table};")

table_client = table_service_client.get_table_client(table_name)

debtor_filter = "debtor eq '{debtor}'".format(debtor = args.debtor)
updated_debtor = '{new_debtor}'.format(new_debtor = args.new_debtor)

print(f"filter:{debtor_filter};")

entities = table_client.query_entities(debtor_filter)

print("-----------------------------START PREVIEW-----------------------------")
for entity in entities:
    key = 'debtor'
    print(f"OLD -> debtor: {entity[key]}, IUV: {entity['RowKey']}")
print("-----------------------------END PREVIEW-----------------------------")

confirm = input("Do you want to update the entity? (yes/no): ")
if confirm.lower() != "yes":
    print("Update aborted.")
    exit()

entities = table_client.query_entities(debtor_filter)

for entity in entities:
    key = 'debtor'
    print(f"OLD -> debtor: {entity[key]}, IUV: {entity['RowKey']}")
    entity[key] = updated_debtor
    # https://learn.microsoft.com/en-us/python/api/overview/azure/data-tables-readme?view=azure-python#entities
    merged_entity = table_client.upsert_entity(mode=UpdateMode.MERGE, entity=entity) 
    print(f"NEW -> debtor: {entity[key]}, IUV: {entity['RowKey']}")