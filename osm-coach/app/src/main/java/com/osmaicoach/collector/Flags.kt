package com.osmaicoach.collector

/**
 * Bandeiras NÍTIDAS sem imagem: nome do país (ou gentílico do filtro "Nacionalidade" do OSM, ou o país no nome da competição,
 * ex. "Alemanha, 3ª Divisão") -> código ISO -> emoji de bandeira do Android (vetorial, legível em qualquer tamanho).
 */
object Flags {
    // iso:nomes separados por vírgula (país e gentílico em português; inglês quando o OSM mostra assim)
    private const val TABLE = """
ar:argentina,argentino;au:australia,australiano;at:austria,austriaco;be:belgica,belga;br:brasil,brasileiro;bg:bulgaria,bulgaro;
ca:canada,canadense;cl:chile,chileno;cn:china,chines;co:colombia,colombiano;hr:croacia,croata;cz:republica tcheca,tcheca,tcheco,chequia;
dk:dinamarca,dinamarques;eg:egito,egipcio;ec:equador,equatoriano;sk:eslovaquia,eslovaco;si:eslovenia,esloveno;es:espanha,espanhol;
us:estados unidos,americano,eua,usa;ee:estonia,estoniano;fi:finlandia,finlandes;fr:franca,frances;de:alemanha,alemao,germany;
gr:grecia,grego;nl:holanda,paises baixos,holandes,neerlandes;hu:hungria,hungaro;ie:irlanda,irlandes;is:islandia,islandes;
il:israel,israelense;it:italia,italiano;jp:japao,japones;lv:letonia,leto;lt:lituania,lituano;lu:luxemburgo,luxemburgues;
mx:mexico,mexicano;no:noruega,noruegues;pl:polonia,polones;pt:portugal,portugues;ro:romenia,romeno;ru:russia,russo;
rs:servia,servio;se:suecia,sueco;ch:suica,suico;tr:turquia,turco;ua:ucrania,ucraniano;uy:uruguai,uruguaio;ve:venezuela,venezuelano;
py:paraguai,paraguaio;pe:peru,peruano;bo:bolivia,boliviano;cr:costa rica,costarriquenho;cu:cuba,cubano;do:republica dominicana,dominicano;
gt:guatemala,guatemalteco;hn:honduras,hondurenho;jm:jamaica,jamaicano;pa:panama,panamenho;sv:el salvador,salvadorenho;
ni:nicaragua,nicaraguense;ht:haiti,haitiano;tt:trinidad e tobago,trinidadiano;
dz:argelia,argelino;ao:angola,angolano;cm:camaroes,camaronês,camaronense,camaroes;ci:costa do marfim,marfinense;cv:cabo verde,cabo-verdiano;
cd:rd congo,congo,congoles;ga:gabao,gabones;gh:gana,ganes;gn:guine,guineense;gw:guine-bissau,guine bissau;ke:quenia,queniano;
ma:marrocos,marroquino;mz:mocambique,mocambicano;ng:nigeria,nigeriano;sn:senegal,senegales;za:africa do sul,sul-africano,sul africano;
tn:tunisia,tunisiano;zm:zambia,zambiano;zw:zimbabue,zimbabuano;ml:mali,maliano;bf:burkina faso,burquinense;tg:togo,togoles;
sa:arabia saudita,saudita;ir:ira,iraniano;iq:iraque,iraquiano;jo:jordania,jordaniano;lb:libano,libanes;sy:siria,sirio;
ae:emirados arabes,emirados arabes unidos;qa:catar,catariano;kr:coreia do sul,sul-coreano,coreano;kp:coreia do norte;
in:india,indiano;id:indonesia,indonesio;th:tailandia,tailandes;vn:vietna,vietnamita;ph:filipinas,filipino;my:malasia,malaio;
nz:nova zelandia,neozelandes;al:albania,albanes;am:armenia,armenio;az:azerbaijao,azerbaijano;by:bielorrussia,bielorrusso;
ba:bosnia,bosnia e herzegovina,bosnio;ge:georgia,georgiano;kz:cazaquistao,cazaque;xk:kosovo,kosovar;
cy:chipre,cipriota;mt:malta,maltes;md:moldavia,moldavo;me:montenegro,montenegrino;mk:macedonia do norte,macedonia,macedonio;
uz:uzbequistao,uzbeque;tj:tadjiquistao,tadjique;kg:quirguistao,quirguiz;tm:turcomenistao,turcomeno;mn:mongolia,mongol;kh:camboja,cambojano;pk:paquistao,paquistanes;bd:bangladesh;lk:sri lanka;np:nepal,nepales;af:afeganistao,afegao;ye:iemen;om:oma,omanense;kw:kuwait,kuwaitiano;bh:bahrein;ps:palestina,palestino;ly:libia,libio;sd:sudao,sudanes;et:etiopia,etiope;ug:uganda,ugandense;tz:tanzania,tanzaniano;rw:ruanda;mg:madagascar,malgaxe;na:namibia,namibio;bw:botsuana;mu:mauricia;lr:liberia,liberiano;sl:serra leoa;gm:gambia,gambiano;cg:congo brazzaville,congoles;gq:guine equatorial;cf:republica centro africana;td:chade;ne:niger;mr:mauritania;ss:sudao do sul;ar2:;au2:;aw:aruba;cw:curacao;sr:suriname,surinames;gy:guiana;bb:barbados;bs:bahamas;bz:belize;pr:porto rico;sg:singapura;tw:taiwan;hk:hong kong;nc:nova caledonia;fj:fiji;pg:papua nova guine;li:liechtenstein;ad:andorra,andorrano;sm:san marino;fo:ilhas faroe,ilhas feroe,faroes;lu2:;
gb-eng:inglaterra,ingles;gb-sct:escocia,escoces;gb-wls:pais de gales,gales,galês,gales;gb-nir:irlanda do norte;gb:reino unido,britanico;
"""

    private val map: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (e in TABLE.replace("\n", "").split(";")) {
            val iso = e.substringBefore(':').trim()
            if (iso.isEmpty() || iso == "lu2") continue
            for (n in e.substringAfter(':').split(",")) {
                val k = Txt.norm(n).trim()
                if (k.isNotEmpty()) m.putIfAbsent(k, iso)
            }
        }
        m
    }

    /** Código ISO do país/gentílico ("Brasileiro", "Alemanha", "Brasileiro ou Argentino" -> primeiro). */
    fun iso(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val first = name.split(Regex("\\s+ou\\s+|/|,")).first().trim()
        val k = Txt.norm(first).trim()
        return map[k] ?: map.entries.firstOrNull { (n, _) -> n.length >= 5 && (k.startsWith("$n ") || k.endsWith(" $n")) }?.value
    }

    /** País no começo do nome da competição ("Alemanha, 3ª Divisão" / "Áustria 2ª Div"); null se não houver. */
    fun isoFromCompetition(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val t = Txt.norm(title).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        // do nome mais longo para o mais curto (evita "guine" casar antes de "guine bissau")
        for ((n, iso) in map.entries.sortedByDescending { it.key.length }) {
            if (n.length >= 4 && (t.startsWith("$n ") || t == n)) return iso
        }
        // "Superliga Uzbeque Uzm": o país/gentílico pode vir depois da 1ª palavra (palavra inteira, nomes de 5+ letras)
        val padded = " $t "
        for ((n, iso) in map.entries.sortedByDescending { it.key.length }) {
            if (n.length >= 5 && padded.contains(" $n ")) return iso
        }
        return null
    }

    /** Emoji da bandeira (letras regionais; Inglaterra/Escócia/Gales usam a sequência de subdivisão). */
    fun emoji(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        if (iso.startsWith("gb-")) {
            val tag = iso.removePrefix("gb-").let { if (it == "sct") "sct" else if (it == "wls") "wls" else if (it == "eng") "eng" else return "🇬🇧" }
            val sb = StringBuilder().appendCodePoint(0x1F3F4)
            for (c in "gb$tag") sb.appendCodePoint(0xE0000 + c.code)
            return sb.appendCodePoint(0xE007F).toString()
        }
        if (iso.length != 2) return null
        val sb = StringBuilder()
        for (c in iso.uppercase()) sb.appendCodePoint(0x1F1E6 + (c - 'A'))
        return sb.toString()
    }

    fun emojiOf(name: String?): String? = emoji(iso(name))
    fun emojiOfCompetition(title: String?): String? = emoji(isoFromCompetition(title))
}
