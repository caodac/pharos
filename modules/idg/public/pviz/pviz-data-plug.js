/**
 * Pulling data from URL and inserting into widget
 */

//window.startUp(ID,seqEntry,processed);
function pvizRender (ID, Seq, features) {
	var pviz = this.pviz; //define the model, a sequence entry with an explicit sequence
    
    	seqEntry = new pviz.SeqEntry({sequence : Seq});
		seqEntry.addFeatures(features);
  
	  /*
   * thefined the view, in a backbone.js fashion
   * model: that's the model, who would have guessed
   * el: a selector to the target where to insert the view (size and so will be inherited)
   *
   * .render(): call the rendering
   *
   * NB: even though the features are not yet added to the model, the view will be recomputed at the end of any feature addition.
   * This is to take into account asynchroncity, when data comes from several remote sources
   */
   
	  new pviz.SeqEntryAnnotInteractiveView({
          model : seqEntry,
          el : ID
        }).render();	  
}

function requestSequence(){
	return seqEntry;
}

function requestProcessed(){
	return processed;
}

function requestID(){
	return ID;
}

function renderDATA(data){
	var arr = []; var arr2 = [];
	for(i = 0; i < data.length; i++){
		arr2.push(data[i].title);
		if(data[i].title === "coiled-coil region" || data[i].title === "transit peptide" ||
				data[i].title === "DNA-binding region"){
			data[i].title = data[i].title.replace(/\s+/g, '_');
		}
		if(data[i].description !== null){
		arr.push({
			'category' : data[i].description,
			'type' : data[i].title,
			'start' : data[i].start,
			'end' : data[i].end
			});
		}else{
			arr.push({
				'category' : "secondary structure",
				'type' : data[i].title,
				'start' : data[i].start,
				'end' : data[i].end
				});
		}
	}
	return arr;
}

function GatherData(SeqURL,DataURL){
	$.getJSON(SeqURL, function(json) {
		  seqEntry = new pviz.SeqEntry({sequence : json});
		})
		.done(function() {
		   pullSequenceDataJSON(DataURL);
		})
}

function pullSequenceDataJSON(URL){
	$.getJSON(URL, function(json) {
		  processed = renderDATA(json);
		  createLists(processed);
		})
		.done(function() {
		   window.startUp();
		})
}

function createLists(data){
	var table = document.getElementById("table");
	data.sort(sortByNum);
	//console.log(data);
	for(var i in data){
		table.insertRow(0).innerHTML += "<td>" + data[i].category + "</td>" + "<td>" + 
		data[i].type + "</td>" + "<td>"+ data[i].start + "</td>" + "<td>"+ data[i].end + "</td>";
	}
	table.insertRow(0).innerHTML += "<th> Category: </th>" + "<th> Type: </th>" + "<th> Start: </th>" + "<th> End: </th>";
}

function sortByAlpha(a,b) {
	  if (a.category < b.category)
	    return 1;
	  if (a.category > b.category)
	    return -1;
	  return 0;
}

function sortByNum(a,b) {
	  if (a.start < b.start)
	    return 1;
	  if (a.start > b.start)
	    return -1;
	  return 0;
}
