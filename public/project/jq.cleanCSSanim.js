/*
* Clean Css Animation f�r jQuery von pfirsichmelba.de // Alexander Farkas <a.farkas@pfirsichmelba.de>
*
* Dual licensed under the MIT (MIT-LICENSE.txt)
* and GPL (GPL-LICENSE.txt) licenses.
* 
* Bei guten Verbesserungen/�ffentlichem Einsatz w�re ich f�r eine kurze Mail mit Link dankbar.
*/
jQuery.fn.CleanCSSAnimation = function(_animetyp,$arg1,$arg2,$arg3,$arg4) {
	var $args = new Array($arg1,$arg2,$arg3,$arg4);
	//Position der Callback-Funktion ist in der Regel der 2. Parameter (1), bei Animate, jedoch der 3. (2)
	//F�r Interface-Nutzer weitere Ausnahmeregeln: (_animetyp == "animate" || _animetyp == "Pulsate" || _animetyp == "Fold" || _animetyp == "UnFold" || _animetyp == "UnFold" || _animetyp == "Highlight") ? $fnpos = 2 : $fnpos = 1;
	(_animetyp == "animate") ? $fnpos = 2 : $fnpos = 1;
	var $cbfn = "";
	//Ist eine CallbackFunktion definiert, wird Sie und ihre Position gespeichert (�berschreibt also die beiden Zeilen davor)
	//Hier wird lediglich die Position ermittelt
	jQuery.each($args,function(o){
		if(typeof this == "function"){
			//Sichern der eigentlichen Callback-Funktion
			$cbfn = $args[o];
			$fnpos = o;
		}
		return;
	});
	//Eigentliches Ersetzen der Callbackfunktion mit der Cleanerfunktion an der erwarteten bzw. ermittelten Position; die alte wird als Parameter �bergeben
	$args.splice($fnpos, 1, function(){_apllyOldCallback(this,$cbfn)});
	return this.each(function(){
	//Verschmutzen des inline-css: vor Animation
	unMakeInlineCSS(this,'pre');
	//Aufruf der jQuery-Animation
	jQuery(this)[ _animetyp ].apply( jQuery(this), $args );
	});
};
//Eigentliche S�uberungsfunktion
function unMakeInlineCSS($elem,$when){
	//Name der css-class die statt display: none/display:block verwendet werden soll.
	var $nodisClass = "displaynone";
	var $disClass = "displayblock";
	if($when == "pre") {
		if(jQuery($elem).is('.'+$nodisClass))
			jQuery($elem).css("display","none").removeClass($nodisClass);
		else if(jQuery($elem).is('.'+$disClass))
			jQuery($elem).css("display","block").removeClass($disClass);
	} else if($when == "post") {
		if(jQuery($elem).css("display") == "none")
			jQuery($elem).addClass($nodisClass).css("display","");
		else if(jQuery($elem).css("display") == "block")
			jQuery($elem).addClass($disClass).css("display","");
	}	
};
// Callbackfunktion startet S�uberung und falls vorhanden die vom Autoren definierte Callbackfunktion
function _apllyOldCallback($elem,$cbfn){
	//S�ubern
	unMakeInlineCSS($elem,"post");
	// Einige Plugin-Effekte (z.B. Interface) rufen die Callback-Funktion auf, bevor die Funktion wirklich beendet wurde -> obigen S�uberungsaufruf l�schen und durch folgenden ersetzen
	/*
	_glob_cleancssele = $elem;
	window.setTimeout("unMakeInlineCSS(_glob_cleancssele,"post")", 3);
	*/
	//Wenn eine Callback-Funktion definiert wurde, kann sie jetzt ausgef�hrt werden
	if(typeof $cbfn == "function")
		$cbfn.apply($elem);
};
