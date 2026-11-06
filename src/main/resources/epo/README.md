# EPO schemas

Official XSD schemas of the Czech tax portal EPO (Finanční správa, adisspr.mfcr.cz).
Downloaded on 2026-11-06 without changes. None of them includes or imports other schemas.

| File | Form | Source | Structure description |
|---|---|---|---|
| `dphkh1_epo2.xsd` | DPHKH1, kontrolní hlášení | https://adisspr.mfcr.cz/adis/jepo/schema/dphkh1_epo2.xsd | https://adisspr.mfcr.cz/dpr/adis/idpr_pub/epo2_info/popis_struktury_detail.faces?zkratka=DPHKH1 (version 03.01.15 of 11. 8. 2026) |
| `dphdp3_epo2.xsd` | DPHDP3, přiznání k DPH | https://adisspr.mfcr.cz/adis/jepo/schema/dphdp3_epo2.xsd | https://adisspr.mfcr.cz/dpr/adis/idpr_pub/epo2_info/popis_struktury_detail.faces?zkratka=DPHDP3 (version 03.01.04 of 11. 8. 2026) |
| `dphshv_epo2.xsd` | DPHSHV, souhrnné hlášení | https://adisspr.mfcr.cz/adis/jepo/schema/dphshv_epo2.xsd | https://adisspr.mfcr.cz/dpr/adis/idpr_pub/epo2_info/popis_struktury_detail.faces?zkratka=DPHSHV (version 02.01.05 of 11. 8. 2026) |

The schemas declare `verzePis` as a free `xs:string`. The builders write the values that EPO itself
writes into files it accepted: `01.02` for DPHKH1 (EPO export of October 2026), `03.01` for DPHDP3
(EPO export of April 2026) and `02.01` for DPHSHV.

To update: download the three files again from the URLs above, run `./gradlew test`, and record the new date here.
