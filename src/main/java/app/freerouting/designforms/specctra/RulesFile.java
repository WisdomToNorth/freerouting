package app.freerouting.designforms.specctra;

import app.freerouting.board.AngleRestriction;
import app.freerouting.board.BasicBoard;
import app.freerouting.core.Padstack;
import app.freerouting.datastructures.IndentFileWriter;
import app.freerouting.interactive.BoardHandling;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.ViaInfo;
import app.freerouting.settings.RouterSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;

/**
 * File for saving the board rules, so that they can be restored after the Board is creates anew
 * from the host system.
 */
public class RulesFile
{

  public static void write(BoardHandling p_board_handling, OutputStream p_output_stream, String p_design_name)
  {
    IndentFileWriter output_file = new IndentFileWriter(p_output_stream);
    BasicBoard routing_board = p_board_handling.get_routing_board();
    WriteScopeParameter write_scope_parameter = new WriteScopeParameter(routing_board, p_board_handling.settings.autoroute_settings, output_file, routing_board.communication.specctra_parser_info.string_quote, routing_board.communication.coordinate_transform, false);
    try
    {
      write_rules(write_scope_parameter, p_design_name);
    } catch (IOException e)
    {
      FRLogger.error("unable to write rules to file", e);
    }
    try
    {
      output_file.close();
    } catch (IOException e)
    {
      FRLogger.error("unable to close rules file", e);
    }
  }

  public static boolean read(InputStream p_input_stream, String p_design_name, BoardHandling p_board_handling)
  {
    BasicBoard routing_board = p_board_handling.get_routing_board();
    IJFlexScanner scanner = new SpecctraDsnStreamReader(p_input_stream);
    try
    {
      Object curr_token = scanner.next_token();
      if (curr_token != Keyword.OPEN_BRACKET)
      {
        FRLogger.warn("RulesFile.read: open bracket expected at '" + scanner.get_scope_identifier() + "'");
        return false;
      }
      curr_token = scanner.next_token();
      if (curr_token != Keyword.RULES)
      {
        FRLogger.warn("RulesFile.read: keyword rules expected at '" + scanner.get_scope_identifier() + "'");
        return false;
      }
      curr_token = scanner.next_token();
      if (curr_token != Keyword.PCB_SCOPE)
      {
        FRLogger.warn("RulesFile.read: keyword pcb expected at '" + scanner.get_scope_identifier() + "'");
        return false;
      }
      scanner.yybegin(SpecctraDsnStreamReader.NAME);
      curr_token = scanner.next_token();
      if (!(curr_token instanceof String) || !curr_token.equals(p_design_name))
      {
        FRLogger.warn("RulesFile.read: design_name not matching at '" + scanner.get_scope_identifier() + "'");
      }
    } catch (IOException e)
    {
      FRLogger.error("RulesFile.read: IO error scanning file", e);
      return false;
    }
    LayerStructure layer_structure = new LayerStructure(routing_board.layer_structure);
    CoordinateTransform coordinate_transform = routing_board.communication.coordinate_transform;
    Object next_token = null;
    for (; ; )
    {
      Object prev_token = next_token;
      try
      {
        next_token = scanner.next_token();
      } catch (IOException e)
      {
        FRLogger.error("RulesFile.read: IO error scanning file", e);
        return false;
      }
      if (next_token == null)
      {
        FRLogger.warn("Structure.read_scope: unexpected end of file at '" + scanner.get_scope_identifier() + "'");
        return false;
      }
      if (next_token == Keyword.CLOSED_BRACKET)
      {
        // end of scope
        break;
      }
      boolean read_ok = true;
      if (prev_token == Keyword.OPEN_BRACKET)
      {
        if (next_token == Keyword.RULE)
        {
          add_rules(Rule.read_scope(scanner), routing_board, null);
        }
        else if (next_token == Keyword.LAYER)
        {
          add_layer_rules(scanner, routing_board);
        }
        else if (next_token == Keyword.PADSTACK)
        {
          Library.read_padstack_scope(scanner, layer_structure, coordinate_transform, routing_board.library.padstacks);
        }
        else if (next_token == Keyword.VIA)
        {
          read_via_info(scanner, routing_board);
        }
        else if (next_token == Keyword.VIA_RULE)
        {
          read_via_rule(scanner, routing_board);
        }
        else if (next_token == Keyword.CLASS)
        {
          read_net_class(scanner, layer_structure, routing_board);
        }
        else if (next_token == Keyword.SNAP_ANGLE)
        {
          AngleRestriction snap_angle = Structure.read_snap_angle(scanner);
          if (snap_angle != null)
          {
            routing_board.rules.set_trace_angle_restriction(snap_angle);
          }
        }
        else if (next_token == Keyword.AUTOROUTE_SETTINGS)
        {
          RouterSettings autoroute_settings = AutorouteSettings.read_scope(scanner, layer_structure);
          if (autoroute_settings != null)
          {
            p_board_handling.settings.autoroute_settings = autoroute_settings;
          }
        }
        else
        {
          ScopeKeyword.skip_scope(scanner);
        }
      }
      if (!read_ok)
      {
        return false;
      }
    }
    return true;
  }

  private static void write_rules(WriteScopeParameter p_par, String p_design_name) throws IOException
  {
    p_par.file.start_scope();
    p_par.file.write("rules PCB ");
    p_par.file.write(p_design_name);
    Structure.write_snap_angle(p_par.file, p_par.board.rules.get_trace_angle_restriction());
    AutorouteSettings.write_scope(p_par.file, p_par.autoroute_settings, p_par.board.layer_structure, p_par.identifier_type);
    // write the default rule using 0 as default layer.
    Rule.write_default_rule(p_par, 0);
    // write the via padstacks
    for (int i = 1; i <= p_par.board.library.padstacks.count(); ++i)
    {
      Padstack curr_padstack = p_par.board.library.padstacks.get(i);
      if (p_par.board.library.get_via_padstack(curr_padstack.name) != null)
      {
        Library.write_padstack_scope(p_par, curr_padstack);
      }
    }
    Network.write_via_infos(p_par.board.rules, p_par.file, p_par.identifier_type);
    Network.write_via_rules(p_par.board.rules, p_par.file, p_par.identifier_type);
    Network.write_net_classes(p_par);
    p_par.file.end_scope();
  }

  private static void add_rules(Collection<Rule> p_rules, BasicBoard p_board, String p_layer_name)
  {
    int layer_no = -1;
    if (p_layer_name != null)
    {
      layer_no = p_board.layer_structure.get_no(p_layer_name);
      if (layer_no < 0)
      {
        FRLogger.warn("RulesFile.add_rules: layer not found at '" + p_layer_name + "'");
      }
    }
    CoordinateTransform coordinate_transform = p_board.communication.coordinate_transform;
    String string_quote = p_board.communication.specctra_parser_info.string_quote;
    for (Rule curr_rule : p_rules)
    {
      if (curr_rule instanceof Rule.WidthRule)
      {
        double wire_width = ((Rule.WidthRule) curr_rule).value;
        int trace_halfwidth = (int) Math.round(coordinate_transform.dsn_to_board(wire_width) / 2);
        if (layer_no < 0)
        {
          p_board.rules.set_default_trace_half_widths(trace_halfwidth);
        }
        else
        {
          p_board.rules.set_default_trace_half_width(layer_no, trace_halfwidth);
        }
      }
      else if (curr_rule instanceof Rule.ClearanceRule)
      {
        Structure.set_clearance_rule((Rule.ClearanceRule) curr_rule, layer_no, coordinate_transform, p_board.rules, string_quote);
      }
    }
  }

  private static boolean add_layer_rules(IJFlexScanner p_scanner, BasicBoard p_board)
  {
    try
    {
      Object next_token = p_scanner.next_token();
      if (!(next_token instanceof String layer_string))
      {
        FRLogger.warn("RulesFile.add_layer_rules: String expected at '" + p_scanner.get_scope_identifier() + "'");
        return false;
      }
      next_token = p_scanner.next_token();
      while (next_token != Keyword.CLOSED_BRACKET)
      {
        if (next_token != Keyword.OPEN_BRACKET)
        {
          FRLogger.warn("RulesFile.add_layer_rules: ( expected at '" + p_scanner.get_scope_identifier() + "'");
          return false;
        }
        next_token = p_scanner.next_token();
        if (next_token == Keyword.RULE)
        {
          Collection<Rule> curr_rules = Rule.read_scope(p_scanner);
          add_rules(curr_rules, p_board, layer_string);
        }
        else
        {
          ScopeKeyword.skip_scope(p_scanner);
        }
        next_token = p_scanner.next_token();
      }
      return true;
    } catch (IOException e)
    {
      FRLogger.error("RulesFile.add_layer_rules: IO error scanning file", e);
      return false;
    }
  }

  private static boolean read_via_info(IJFlexScanner p_scanner, BasicBoard p_board)
  {
    ViaInfo curr_via_info = Network.read_via_info(p_scanner, p_board);
    if (curr_via_info == null)
    {
      return false;
    }
    ViaInfo existing_via = p_board.rules.via_infos.get(curr_via_info.get_name());
    if (existing_via != null)
    {
      // replace existing via info
      p_board.rules.via_infos.remove(existing_via);
    }
    p_board.rules.via_infos.add(curr_via_info);
    return true;
  }

  private static boolean read_via_rule(IJFlexScanner p_scanner, BasicBoard p_board)
  {
    Collection<String> via_rule = Network.read_via_rule(p_scanner, p_board);
    if (via_rule == null)
    {
      return false;
    }
    Network.add_via_rule(via_rule, p_board);
    return true;
  }

  private static boolean read_net_class(IJFlexScanner p_scanner, LayerStructure p_layer_structure, BasicBoard p_board)
  {
    NetClass curr_class = NetClass.read_scope(p_scanner);
    if (curr_class == null)
    {
      return false;
    }
    Network.insert_net_class(curr_class, p_layer_structure, p_board, p_board.communication.coordinate_transform, false);
    return true;
  }
}