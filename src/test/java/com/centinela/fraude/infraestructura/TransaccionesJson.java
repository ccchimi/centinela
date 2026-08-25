package com.centinela.fraude.infraestructura;

/**
 * Construye la carga útil JSON tal y como la mandaría el sistema de autorización.
 *
 * <p>El JSON se escribe a mano, no se serializa con Jackson desde un DTO. Es intencionado: si el
 * test construyera el mensaje con el mismo serializador que usa el consumidor, cualquier cambio
 * en el contrato de cable seguiría pasando los tests. Escribiéndolo literalmente, el test falla
 * cuando el contrato cambia, que es exactamente lo que un contrato con terceros debe hacer.
 */
public final class TransaccionesJson {

    private TransaccionesJson() {}

    /** Compra en comercio electrónico, con geolocalización y huella de dispositivo. */
    public static String compraWeb(
            String idTransaccion,
            String idCliente,
            String importe,
            String comercioId,
            String mcc,
            String paisComercio,
            double latitud,
            double longitud,
            String paisOrigen,
            String idDispositivo,
            String ocurridaEn) {
        return """
               {
                 "idTransaccion": "%s",
                 "idCliente": "%s",
                 "importe": %s,
                 "moneda": "EUR",
                 "instrumento": {
                   "tipo": "TARJETA",
                   "bin": "454213",
                   "ultimosCuatro": "1881",
                   "marca": "VISA",
                   "presente": false
                 },
                 "comercio": {
                   "id": "%s",
                   "nombre": "Comercio de prueba",
                   "mcc": "%s",
                   "pais": "%s"
                 },
                 "canal": "WEB",
                 "origen": { "latitud": %s, "longitud": %s, "pais": "%s" },
                 "idDispositivo": "%s",
                 "ocurridaEn": "%s"
               }
               """
                .formatted(
                        idTransaccion,
                        idCliente,
                        importe,
                        comercioId,
                        mcc,
                        paisComercio,
                        latitud,
                        longitud,
                        paisOrigen,
                        idDispositivo,
                        ocurridaEn);
    }

    /** Igual que {@link #compraWeb} pero sin huella de dispositivo: degrada una regla. */
    public static String compraWebSinDispositivo(
            String idTransaccion, String idCliente, String importe, String ocurridaEn) {
        return """
               {
                 "idTransaccion": "%s",
                 "idCliente": "%s",
                 "importe": %s,
                 "moneda": "EUR",
                 "instrumento": {
                   "tipo": "TARJETA",
                   "bin": "454213",
                   "ultimosCuatro": "1881",
                   "marca": "VISA",
                   "presente": false
                 },
                 "comercio": { "id": "com-100", "nombre": "Libreria", "mcc": "5942", "pais": "ES" },
                 "canal": "WEB",
                 "origen": { "latitud": 40.4168, "longitud": -3.7038, "pais": "ES" },
                 "ocurridaEn": "%s"
               }
               """
                .formatted(idTransaccion, idCliente, importe, ocurridaEn);
    }

    /** JSON sintácticamente roto: no cierra la llave. */
    public static String malformado() {
        return "{ \"idTransaccion\": \"da-igual\", \"esto\": ";
    }

    /** JSON válido cuyo contenido no supera la validación: falta el cliente y el canal no existe. */
    public static String sinCliente(String idTransaccion, String ocurridaEn) {
        return """
               {
                 "idTransaccion": "%s",
                 "importe": 10.00,
                 "moneda": "EUR",
                 "instrumento": {
                   "tipo": "TARJETA", "bin": "454213", "ultimosCuatro": "1881",
                   "marca": "VISA", "presente": false
                 },
                 "comercio": { "id": "com-100", "nombre": "Libreria", "mcc": "5942", "pais": "ES" },
                 "canal": "WEB",
                 "ocurridaEn": "%s"
               }
               """
                .formatted(idTransaccion, ocurridaEn);
    }

    /** JSON válido con un tipo de instrumento que el traductor no conoce. */
    public static String instrumentoDesconocido(String idTransaccion, String idCliente, String ocurridaEn) {
        return """
               {
                 "idTransaccion": "%s",
                 "idCliente": "%s",
                 "importe": 10.00,
                 "moneda": "EUR",
                 "instrumento": { "tipo": "CHEQUE_AL_PORTADOR" },
                 "comercio": { "id": "com-100", "nombre": "Libreria", "mcc": "5942", "pais": "ES" },
                 "canal": "WEB",
                 "ocurridaEn": "%s"
               }
               """
                .formatted(idTransaccion, idCliente, ocurridaEn);
    }
}
