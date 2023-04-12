DROP TABLE SINGLE_REFERENCE;
DROP TABLE SINGLE_SET;
DROP TABLE SINGLE_LIST;
DROP TABLE SINGLE_MAP;
DROP TABLE DUMMY_ENTITY;

CREATE TABLE SINGLE_REFERENCE
(
    ID   INTEGER GENERATED by default on null as IDENTITY PRIMARY KEY
);

CREATE TABLE SINGLE_SET
(
    ID   INTEGER GENERATED by default on null as IDENTITY PRIMARY KEY
);

CREATE TABLE SINGLE_LIST
(
    ID   INTEGER GENERATED by default on null as IDENTITY PRIMARY KEY
);

CREATE TABLE SINGLE_MAP
(
    ID   INTEGER GENERATED by default on null as IDENTITY PRIMARY KEY
);

CREATE TABLE DUMMY_ENTITY
(
    ID   INTEGER GENERATED by default on null as IDENTITY PRIMARY KEY,
    NAME VARCHAR(30),
    SINGLE_REFERENCE INTEGER,
    SINGLE_SET INTEGER,
    SINGLE_LIST_KEY INTEGER,
    SINGLE_LIST INTEGER,
    SINGLE_MAP_KEY VARCHAR(10),
    SINGLE_MAP INTEGER
);
