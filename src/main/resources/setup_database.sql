\set ON_ERROR_STOP on

CREATE USER helper_oly WITH PASSWORD :'helper_oly_password';
CREATE DATABASE telegram_bot OWNER helper_oly;

\connect telegram_bot

GRANT USAGE, CREATE ON SCHEMA public TO helper_oly;
