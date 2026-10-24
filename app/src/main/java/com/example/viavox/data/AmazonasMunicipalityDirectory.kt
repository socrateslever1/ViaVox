package com.example.viavox.data

/** All 62 Amazonas municipalities, available on the very first offline launch.
 * Source for names and IBGE codes: https://ciam.net.br/municipios
 * Cross-reference: https://aam.org.br/municipios/
 * Online IBGE API refreshes/corrects the directory every 30 days.
 */
object AmazonasMunicipalityDirectory {
    val all: List<Pair<Int,String>> = listOf(
        1300029 to "Alvarães",
        1300060 to "Amaturá",
        1300086 to "Anamã",
        1300102 to "Anori",
        1300144 to "Apuí",
        1300201 to "Atalaia do Norte",
        1300300 to "Autazes",
        1300409 to "Barcelos",
        1300508 to "Barreirinha",
        1300607 to "Benjamin Constant",
        1300631 to "Beruri",
        1300680 to "Boa Vista do Ramos",
        1300706 to "Boca do Acre",
        1300805 to "Borba",
        1300839 to "Caapiranga",
        1300904 to "Canutama",
        1301001 to "Carauari",
        1301100 to "Careiro",
        1301159 to "Careiro da Várzea",
        1301209 to "Coari",
        1301308 to "Codajás",
        1301407 to "Eirunepé",
        1301506 to "Envira",
        1301605 to "Fonte Boa",
        1301654 to "Guajará",
        1301704 to "Humaitá",
        1301803 to "Ipixuna",
        1301852 to "Iranduba",
        1301902 to "Itacoatiara",
        1301951 to "Itamarati",
        1302009 to "Itapiranga",
        1302108 to "Japurá",
        1302207 to "Juruá",
        1302306 to "Jutaí",
        1302405 to "Lábrea",
        1302504 to "Manacapuru",
        1302553 to "Manaquiri",
        1302603 to "Manaus",
        1302702 to "Manicoré",
        1302801 to "Maraã",
        1302900 to "Maués",
        1303007 to "Nhamundá",
        1303106 to "Nova Olinda do Norte",
        1303205 to "Novo Airão",
        1303304 to "Novo Aripuanã",
        1303403 to "Parintins",
        1303502 to "Pauini",
        1303536 to "Presidente Figueiredo",
        1303569 to "Rio Preto da Eva",
        1303601 to "Santa Isabel do Rio Negro",
        1303700 to "Santo Antônio do Içá",
        1303809 to "São Gabriel da Cachoeira",
        1303908 to "São Paulo de Olivença",
        1303957 to "São Sebastião do Uatumã",
        1304005 to "Silves",
        1304062 to "Tabatinga",
        1304104 to "Tapauá",
        1304203 to "Tefé",
        1304237 to "Tonantins",
        1304260 to "Uarini",
        1304302 to "Urucará",
        1304401 to "Urucurituba"
    )
    init {
        require(all.size == 62 && all.map { it.first }.distinct().size == 62)
    }
    fun codeOf(name: String): Int? =
        all.firstOrNull { GeoCatalogDatabase.fold(it.second) ==
            GeoCatalogDatabase.fold(name) }?.first
}
