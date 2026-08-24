package com.centinela.fraude.dominio.modelo.vo;

/** Red de la tarjeta. Condiciona el formato del BIN y las reglas de autenticación reforzada. */
public enum MarcaDeTarjeta {
    VISA,
    MASTERCARD,
    AMEX,
    DISCOVER,
    UNIONPAY,
    OTRA
}
