package com.centinela.fraude.dominio.modelo.vo;

import com.centinela.fraude.dominio.compartido.Invariantes;

/**
 * Medio de pago empleado en la transacción.
 *
 * <p>Jerarquía <strong>sellada</strong>: el conjunto de instrumentos que el motor sabe evaluar
 * es cerrado y conocido en tiempo de compilación. Eso permite recorrerla con {@code switch}
 * exhaustivo sobre patrones de registro y, sobre todo, hace que añadir un instrumento nuevo
 * rompa la compilación en todos los puntos que deben decidir sobre él, en lugar de caer en un
 * {@code default} que lo trataría como riesgo bajo por accidente.
 *
 * <p><strong>PCI-DSS:</strong> ninguna variante almacena el PAN completo. El BIN (6-8 dígitos)
 * y los últimos cuatro son datos no sensibles y bastan para todas las señales del motor.
 */
public sealed interface InstrumentoDePago {

    /**
     * Aporte de riesgo intrínseco al instrumento, antes de mirar comportamiento o contexto.
     *
     * <p>Ejemplo de {@code switch} exhaustivo con deconstrucción de registros y guardas: el
     * compilador verifica que se cubren todas las variantes sin necesidad de {@code default}.
     */
    default int riesgoIntrinseco() {
        return switch (this) {
            // Tarjeta no presente: el vector de fraude dominante en comercio electrónico.
            case Tarjeta(_, _, _, boolean presente) when !presente -> 120;
            case Tarjeta _ -> 20;

            // Billetera tokenizada con autenticación biométrica en el dispositivo.
            case BilleteraDigital(_, _, boolean tokenizada) when tokenizada -> 15;
            case BilleteraDigital _ -> 90;

            // Transferencia inmediata: irreversible una vez liquidada.
            case TransferenciaBancaria _ -> 150;
        };
    }

    /** Descripción sin datos sensibles, apta para logs y para el registro de auditoría. */
    String descripcionSegura();

    /**
     * Tarjeta de pago.
     *
     * @param bin Bank Identification Number, 6 u 8 dígitos
     * @param ultimosCuatro últimos cuatro dígitos del PAN
     * @param presente true si la tarjeta se leyó físicamente (chip, banda, contactless)
     */
    record Tarjeta(String bin, String ultimosCuatro, MarcaDeTarjeta marca, boolean presente) implements InstrumentoDePago {

        public Tarjeta {
            bin = Invariantes.textoConContenido(bin, "tarjeta.bin");
            Invariantes.exigir(
                    (bin.length() == 6 || bin.length() == 8) && bin.chars().allMatch(Character::isDigit),
                    "'tarjeta.bin' debe tener 6 u 8 dígitos, pero fue '%s'",
                    bin);
            ultimosCuatro = Invariantes.digitos(ultimosCuatro, "tarjeta.ultimosCuatro", 4);
            Invariantes.noNulo(marca, "tarjeta.marca");
        }

        @Override
        public String descripcionSegura() {
            return "%s %s******%s".formatted(marca, bin.substring(0, 4), ultimosCuatro);
        }
    }

    /**
     * Billetera digital (Apple Pay, Google Pay, PayPal y equivalentes).
     *
     * @param tokenizada true si el pago viaja con un token de red respaldado por autenticación
     *     en el dispositivo; false si la billetera es solo un contenedor de credenciales
     */
    record BilleteraDigital(String proveedor, IdDispositivo dispositivo, boolean tokenizada) implements InstrumentoDePago {

        public BilleteraDigital {
            proveedor = Invariantes.textoConContenido(proveedor, "billetera.proveedor", 64);
            Invariantes.noNulo(dispositivo, "billetera.dispositivo");
        }

        @Override
        public String descripcionSegura() {
            return "%s (%s)".formatted(proveedor, tokenizada ? "tokenizada" : "sin tokenizar");
        }
    }

    /**
     * Transferencia bancaria inmediata.
     *
     * @param ibanEnmascarado IBAN con el cuerpo ya ofuscado por el adaptador de entrada
     * @param bic código de identificación de la entidad ordenante
     */
    record TransferenciaBancaria(String ibanEnmascarado, String bic) implements InstrumentoDePago {

        public TransferenciaBancaria {
            ibanEnmascarado = Invariantes.textoConContenido(ibanEnmascarado, "transferencia.iban", 34);
            bic = Invariantes.textoConContenido(bic, "transferencia.bic", 11).toUpperCase(java.util.Locale.ROOT);
            Invariantes.exigir(
                    bic.length() == 8 || bic.length() == 11, "'transferencia.bic' debe tener 8 u 11 caracteres");
        }

        @Override
        public String descripcionSegura() {
            return "Transferencia %s".formatted(bic);
        }
    }
}
