CREATE DATABASE telegram_bot;


CREATE USER helper_oly WITH PASSWORD 'mashmallow';


GRANT ALL PRIVILEGES ON DATABASE telegram_bot TO helper_oly;


\c telegram_bot;

GRANT ALL ON SCHEMA public TO helper_oly;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO helper_oly;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO helper_oly;


ALTER USER helper_oly CREATEDB;